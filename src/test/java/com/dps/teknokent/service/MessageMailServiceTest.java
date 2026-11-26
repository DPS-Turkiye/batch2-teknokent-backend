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
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.CoordinatorConversation;
import com.dps.teknokent.entity.CoordinatorMessage;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.MentorshipRequestMessage;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.CoordinatorConversationRepository;
import com.dps.teknokent.repository.CoordinatorMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestMessageRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import jakarta.persistence.EntityManager;

@SpringBootTest
@Transactional
class MessageMailServiceTest {

	// A Wednesday, 10:00 in Turkey.
	private static final Instant MORNING = Instant.parse("2026-11-25T07:00:00Z");

	@Autowired
	private MentorshipRequestRepository requestRepository;
	@Autowired
	private MentorshipRequestMessageRepository requestMessageRepository;
	@Autowired
	private CoordinatorConversationRepository conversationRepository;
	@Autowired
	private CoordinatorMessageRepository coordinatorMessageRepository;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private ProgramRepository programRepository;
	@Autowired
	private ProgramCoordinatorRepository programCoordinatorRepository;
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
	private AppUser member;
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
		member = user("Can Er", UserRole.MENTEE, team);
		program = program();
	}

	@Test
	void mailsTheRequesterOnceAboutTheMentorsMessageLeftUnreadForAQuarterOfAnHour() {
		MentorshipRequest request = request(MentorshipRequestStatus.ACCEPTED);
		message(request, mentor, MORNING.minus(Duration.ofMinutes(16)));
		message(request, mentor, MORNING.minus(Duration.ofMinutes(20)));

		service(MORNING).mailRequesters(MORNING);
		service(MORNING).mailRequesters(MORNING.plusSeconds(60));

		assertThat(mailed(founder, 1).getFirst()).contains("Leyla Aksoy",
				"https://menton.com.tr/mentor-request/status?request=" + request.getId());
		verify(mailService, never()).sendAfterCommit(eq(mentor.getEmail()), any(), any(), any());
	}

	@Test
	void theRequestersOwnFreshReadOrClosedMessagesAreNotMailed() {
		message(request(MentorshipRequestStatus.ACCEPTED), founder, MORNING.minus(Duration.ofMinutes(30)));
		message(request(MentorshipRequestStatus.ACCEPTED), mentor, MORNING.minus(Duration.ofMinutes(10)));
		MentorshipRequest read = request(MentorshipRequestStatus.ACCEPTED);
		message(read, mentor, MORNING.minus(Duration.ofMinutes(30)));
		read = requestRepository.findById(read.getId()).orElseThrow();
		read.markReadBy(member, MORNING.minus(Duration.ofMinutes(20)));
		requestRepository.saveAndFlush(read);
		message(request(MentorshipRequestStatus.DECLINED), mentor, MORNING.minus(Duration.ofMinutes(30)));
		message(request(MentorshipRequestStatus.ACCEPTED), mentor, MORNING.minus(Duration.ofHours(25)));

		service(MORNING).mailRequesters(MORNING);

		verify(mailService, never()).sendAfterCommit(any(), any(), any(), any());
	}

	@Test
	void mailsEveryTeamMemberOnceAboutTheCoordinatorsUnreadMessage() {
		CoordinatorConversation thread = conversation(team.getId(), null);
		post(thread, coordinator, MORNING.minus(Duration.ofMinutes(20)));

		service(MORNING).mailConversations(MORNING);
		service(MORNING).mailConversations(MORNING.plusSeconds(60));

		assertThat(mailed(founder, 1).getFirst()).contains("Sena Koray",
				"https://menton.com.tr/messages?conversation=" + thread.getId());
		mailed(member, 1);
		verify(mailService, never()).sendAfterCommit(eq(coordinator.getEmail()), any(), any(), any());
	}

	@Test
	void mailsTheCoordinatorAboutTheTeamsReplyAndNotTheTeamAgain() {
		CoordinatorConversation thread = conversation(team.getId(), null);
		post(thread, coordinator, MORNING.minus(Duration.ofMinutes(40)));
		post(thread, founder, MORNING.minus(Duration.ofMinutes(16)));
		markRead(thread, MORNING.minus(Duration.ofMinutes(17)), null);

		service(MORNING).mailConversations(MORNING);

		assertThat(mailed(coordinator, 1).getFirst()).contains("Deniz Ak",
				"https://menton.com.tr/coordinator/messages?conversation=" + thread.getId());
		verify(mailService, never()).sendAfterCommit(eq(founder.getEmail()), any(), any(), any());
		verify(mailService, never()).sendAfterCommit(eq(member.getEmail()), any(), any(), any());
	}

	@Test
	void mailsTheMentorOfAMentorThreadAndAgainOnlyForALaterMessage() {
		CoordinatorConversation thread = conversation(null, mentor.getId());
		post(thread, coordinator, MORNING.minus(Duration.ofMinutes(16)));
		service(MORNING).mailConversations(MORNING);
		Instant later = MORNING.plus(Duration.ofHours(2));
		service(later).mailConversations(later);
		post(thread, coordinator, later.minus(Duration.ofMinutes(16)));

		service(later).mailConversations(later);

		assertThat(mailed(mentor, 2).getFirst()).contains(
				"https://menton.com.tr/mentor-dashboard/messages?conversation=" + thread.getId());
	}

	@Test
	void aFreshOrAlreadyReadConversationMessageIsNotMailed() {
		post(conversation(team.getId(), null), coordinator, MORNING.minus(Duration.ofMinutes(10)));
		CoordinatorConversation read = conversation(null, mentor.getId());
		post(read, coordinator, MORNING.minus(Duration.ofMinutes(30)));
		markRead(read, MORNING.minus(Duration.ofMinutes(20)), null);

		service(MORNING).mailConversations(MORNING);

		verify(mailService, never()).sendAfterCommit(any(), any(), any(), any());
	}

	@Test
	void theSchedulerRunsOnlyWhereTheMailsAreOn() {
		ApplicationContextRunner runner = new ApplicationContextRunner()
				.withBean(MessageMailService.class, () -> mock(MessageMailService.class))
				.withUserConfiguration(MessageMailScheduler.class);

		runner.run(context -> assertThat(context).doesNotHaveBean(MessageMailScheduler.class));
		runner.withPropertyValues("app.mail.activity.enabled=true").run(context -> {
			context.getBean(MessageMailScheduler.class).mailUnreadMessages();
			verify(context.getBean(MessageMailService.class)).mailUnreadMessages();
		});
	}

	private MessageMailService service(Instant now) {
		return new MessageMailService(requestRepository, conversationRepository, coordinatorMessageRepository,
				programRepository, programCoordinatorRepository, userRepository,
				new ActivityMailService(new NotificationMailer(mailService, preferenceService), true),
				transactionManager, Clock.fixed(now, MeetingSlotTimes.APP_ZONE));
	}

	private List<String> mailed(AppUser reader, int times) {
		ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
		verify(mailService, times(times)).sendAfterCommit(eq(reader.getEmail()), anyString(), body.capture(), any());
		return body.getAllValues();
	}

	private MentorshipRequest request(MentorshipRequestStatus status) {
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
		return requestRepository.saveAndFlush(request);
	}

	private void message(MentorshipRequest request, AppUser author, Instant at) {
		MentorshipRequestMessage message = requestMessageRepository.saveAndFlush(MentorshipRequestMessage.create(
				request, author, MentorshipRequestMessageType.MENTOR_MESSAGE, "Merhaba"));
		entityManager.createNativeQuery("update mentorship_request_message set created_at = ?1 where id = ?2")
				.setParameter(1, Timestamp.from(at))
				.setParameter(2, message.getId())
				.executeUpdate();
	}

	private CoordinatorConversation conversation(UUID organizationId, UUID mentorId) {
		CoordinatorConversation conversation = new CoordinatorConversation();
		conversation.setId(UUID.randomUUID());
		conversation.setProgramId(program.getId());
		conversation.setOrganizationId(organizationId);
		conversation.setMentorId(mentorId);
		conversation.setCreatedAt(MORNING.minus(Duration.ofDays(2)));
		return conversationRepository.saveAndFlush(conversation);
	}

	private void post(CoordinatorConversation conversation, AppUser sender, Instant at) {
		CoordinatorMessage message = new CoordinatorMessage();
		message.setId(UUID.randomUUID());
		message.setConversationId(conversation.getId());
		message.setSenderId(sender.getId());
		message.setBody("Merhaba");
		message.setCreatedAt(at);
		coordinatorMessageRepository.saveAndFlush(message);
		CoordinatorConversation current = conversationRepository.findById(conversation.getId()).orElseThrow();
		if (current.getLastMessageAt() == null || at.isAfter(current.getLastMessageAt())) {
			current.setLastMessageAt(at);
			conversationRepository.saveAndFlush(current);
		}
	}

	private void markRead(CoordinatorConversation conversation, Instant counterpartAt, Instant coordinatorAt) {
		CoordinatorConversation current = conversationRepository.findById(conversation.getId()).orElseThrow();
		current.setCounterpartReadAt(counterpartAt);
		current.setCoordinatorReadAt(coordinatorAt);
		conversationRepository.saveAndFlush(current);
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@messages.test");
		user.setRole(role);
		user.setStatus(UserAccountStatus.APPROVED);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Program program() {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName("Kuluçka 2026");
		created.setCreatedBy(coordinator.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		return programRepository.saveAndFlush(created);
	}
}
