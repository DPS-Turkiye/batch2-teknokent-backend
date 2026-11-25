package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.NotificationPreference;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.NotificationPreferenceRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class RequestFollowUpServiceTest {

	// A Wednesday, 10:00 in Turkey.
	private static final Instant MORNING = Instant.parse("2026-11-25T07:00:00Z");

	@Autowired
	private MentorshipRequestRepository requestRepository;
	@Autowired
	private MentorshipRequestMessageRepository messageRepository;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private ProgramRepository programRepository;
	@Autowired
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Autowired
	private NotificationRepository notificationRepository;
	@Autowired
	private NotificationPreferenceRepository preferenceRepository;
	@Autowired
	private NotificationService notificationService;
	@Autowired
	private NotificationPreferenceService preferenceService;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private EntityManager entityManager;

	private final MailService mailService = mock(MailService.class);

	private AppUser coordinator;
	private AppUser mentor;
	private AppUser founder;
	private Organization team;
	private Program program;

	@BeforeEach
	void setUp() {
		when(mailService.link(anyString())).thenAnswer(call -> "https://menton.com.tr" + call.getArgument(0));
		coordinator = user("Sena Koray", UserRole.COORDINATOR, null);
		mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		team = new Organization();
		team.setName("Döngü");
		team = organizationRepository.saveAndFlush(team);
		founder = user("Deniz Ak", UserRole.MENTEE, team);
		program = program(ProgramStatus.ACTIVE);
	}

	@Test
	void remindsAMentorOnceForAllTheRequestsWaitingTwoDays() {
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(3)));
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(2)));
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(1)));
		request(MentorshipRequestStatus.ACCEPTED, MORNING.minus(Duration.ofDays(3)));

		service(MORNING).remindMentors(MORNING);
		service(MORNING).remindMentors(MORNING);

		assertThat(notifications(mentor)).singleElement()
				.satisfies(notice -> assertThat(notice.getBody()).startsWith("2 talep"));
		assertThat(mailed(mentor, 1).getFirst()).contains("Merhaba Leyla Aksoy", "2 mentorluk talebi",
				"https://menton.com.tr/mentor-dashboard", "E-posta bildirimleri");
	}

	@Test
	void leavesOldRequestsAndFinishedProgramsAlone() {
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(31)));
		program = program(ProgramStatus.COMPLETED);
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(5)));

		service(MORNING).remindMentors(MORNING);
		service(MORNING).alertCoordinators(MORNING);

		assertThat(notifications(mentor)).isEmpty();
		assertThat(notifications(coordinator)).isEmpty();
		verify(mailService, never()).sendAfterCommit(any(), any(), any(), any());
	}

	@Test
	void tellsTheProgramsCoordinatorOnceWhoHasNotAnsweredForThreeDays() {
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(4)));
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(3)));
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(2)));
		AppUser otherMentor = mentor;
		mentor = user("Ali Can", UserRole.MENTOR, null);
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(5)));
		mentor = otherMentor;

		service(MORNING).alertCoordinators(MORNING);
		service(MORNING).alertCoordinators(MORNING);

		assertThat(notifications(coordinator)).singleElement().satisfies(notice -> {
			assertThat(notice.getTitle()).startsWith("Kuluçka 2026");
			assertThat(notice.getBody()).contains("3 talep", "Ali Can (1), Leyla Aksoy (2)");
			assertThat(notice.getHref()).isEqualTo("/coordinator/programs/" + program.getId());
		});
		assertThat(mailed(coordinator, 1).getFirst()).contains("Kuluçka 2026", "Ali Can (1), Leyla Aksoy (2)");
	}

	@Test
	void remindersWaitForWorkingHoursInTurkey() {
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(4)));
		Instant night = Instant.parse("2026-11-25T20:00:00Z");

		service(night).followUp();
		assertThat(notifications(mentor)).isEmpty();

		service(MORNING).followUp();
		assertThat(notifications(mentor)).hasSize(1);
		assertThat(notifications(coordinator)).hasSize(1);
	}

	@Test
	void mailsTheMentorOnceAboutMessagesLeftUnreadForAQuarterOfAnHour() {
		MentorshipRequest request = request(MentorshipRequestStatus.ACCEPTED, MORNING.minus(Duration.ofDays(1)));
		message(request, founder, MORNING.minus(Duration.ofMinutes(16)));
		message(request, founder, MORNING.minus(Duration.ofMinutes(20)));

		service(MORNING).mailUnreadMessages(MORNING);
		service(MORNING).mailUnreadMessages(MORNING.plusSeconds(60));

		assertThat(mailed(mentor, 1).getFirst()).contains("Deniz Ak",
				"https://menton.com.tr/mentor-dashboard/messages");
		assertThat(reload(request).getUpdatedAt()).isEqualTo(request.getUpdatedAt());
	}

	@Test
	void aLaterMessageIsMailedAgain() {
		MentorshipRequest request = request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(1)));
		message(request, founder, MORNING.minus(Duration.ofMinutes(16)));
		service(MORNING).mailUnreadMessages(MORNING);
		Instant later = MORNING.plus(Duration.ofHours(2));
		message(request, founder, later.minus(Duration.ofMinutes(16)));

		service(later).mailUnreadMessages(later);

		mailed(mentor, 2);
	}

	@Test
	void aMessageThatIsFreshReadOldOrTheMentorsOwnIsNotMailed() {
		message(request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(1))), founder,
				MORNING.minus(Duration.ofMinutes(14)));
		MentorshipRequest read = request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(1)));
		read.markReadBy(mentor, MORNING.minus(Duration.ofMinutes(10)));
		message(read, founder, MORNING.minus(Duration.ofMinutes(30)));
		message(request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(3))), founder,
				MORNING.minus(Duration.ofHours(25)));
		message(request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(1))), mentor,
				MORNING.minus(Duration.ofMinutes(30)));
		message(request(MentorshipRequestStatus.DECLINED, MORNING.minus(Duration.ofDays(1))), founder,
				MORNING.minus(Duration.ofMinutes(30)));

		service(MORNING).mailUnreadMessages(MORNING);

		verify(mailService, never()).sendAfterCommit(any(), any(), any(), any());
	}

	@Test
	void theMailAsksForTheReadersOwnSwitchJustBeforeItLeaves() {
		request(MentorshipRequestStatus.PENDING, MORNING.minus(Duration.ofDays(2)));
		service(MORNING).remindMentors(MORNING);
		ArgumentCaptor<BooleanSupplier> wanted = ArgumentCaptor.forClass(BooleanSupplier.class);
		verify(mailService).sendAfterCommit(eq(mentor.getEmail()), anyString(), anyString(), wanted.capture());

		assertThat(wanted.getValue().getAsBoolean()).isTrue();
		NotificationPreference off = NotificationPreference.defaultsFor(mentor.getId());
		off.setEmails(false);
		preferenceRepository.saveAndFlush(off);
		assertThat(wanted.getValue().getAsBoolean()).isFalse();
	}

	@Test
	void sendsNoMailWhileTheDeploymentHasThemOff() {
		new ActivityMailService(new NotificationMailer(mailService, preferenceService), false)
				.send(mentor, NotificationCategory.GENERAL, "mail.requestsWaiting", "/", 1);

		verify(mailService, never()).sendAfterCommit(any(), any(), any(), any());
	}

	@Test
	void theSchedulerRunsOnlyWhereTheMailsAreOn() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(RequestFollowUpService.class, () -> mock(RequestFollowUpService.class))
				.withUserConfiguration(RequestFollowUpScheduler.class);

		runner.run(context -> assertThat(context).doesNotHaveBean(RequestFollowUpScheduler.class));
		runner.withPropertyValues("app.mail.activity.enabled=true").run(context -> {
			context.getBean(RequestFollowUpScheduler.class).followUp();
			verify(context.getBean(RequestFollowUpService.class)).followUp();
		});
	}

	private RequestFollowUpService service(Instant now) {
		return new RequestFollowUpService(requestRepository, programCoordinatorRepository, userRepository,
				notificationService,
				new ActivityMailService(new NotificationMailer(mailService, preferenceService), true),
				transactionManager, Clock.fixed(now, MeetingSlotTimes.APP_ZONE));
	}

	private List<String> mailed(AppUser reader, int times) {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mailService, times(times)).sendAfterCommit(eq(reader.getEmail()), anyString(), body.capture(), any());
		return body.getAllValues();
	}

	private List<Notification> notifications(AppUser reader) {
		return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(reader.getId());
	}

	private MentorshipRequest request(MentorshipRequestStatus status, Instant sentAt) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(founder);
		request.setOrganization(team);
		request.setMentor(mentor);
		request.setProgram(program);
		request.setRequestedForName("Döngü");
		request.setSupportArea("Finans");
		request.setSummary("Finans");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(status);
		MentorshipRequest saved = requestRepository.saveAndFlush(request);
		moveTo("mentorship_request", saved.getId(), sentAt);
		return saved;
	}

	private void message(MentorshipRequest request, AppUser author, Instant at) {
		MentorshipRequestMessage message = messageRepository.saveAndFlush(MentorshipRequestMessage.create(
				request, author, MentorshipRequestMessageType.MENTEE_MESSAGE, "Merhaba"));
		moveTo("mentorship_request_message", message.getId(), at);
	}

	private void moveTo(String table, UUID id, Instant createdAt) {
		entityManager.createNativeQuery("update " + table + " set created_at = ?1 where id = ?2")
				.setParameter(1, Timestamp.from(createdAt))
				.setParameter(2, id)
				.executeUpdate();
	}

	private MentorshipRequest reload(MentorshipRequest request) {
		return requestRepository.findById(request.getId()).orElseThrow();
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@followup.test");
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Program program(ProgramStatus status) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName("Kuluçka 2026");
		created.setCreatedBy(coordinator.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(status);
		return programRepository.saveAndFlush(created);
	}
}
