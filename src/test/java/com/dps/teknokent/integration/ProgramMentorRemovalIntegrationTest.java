package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.MentorshipStatus;
import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramMentorInvite;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramMentorInviteRepository;
import com.dps.teknokent.repository.ProgramMentorRemovalRepository;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.MenteeProgramService;

/** An admin takes a mentor out of a program: the mentor leaves its pool, what already began there goes on. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramMentorRemovalIntegrationTest {

	private static final String REMOVE = "/api/admin/programs/{programId}/mentors/{mentorId}/remove";
	private static final String ASSIGN = "/api/admin/programs/{programId}/mentors/{mentorId}";
	private static final String REQUEST_HREF = "/mentor-request/status?request=";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private ProgramRepository programRepository;
	@Autowired
	private ProgramCoordinatorRepository programCoordinatorRepository;
	@Autowired
	private ProgramStartupRepository programStartupRepository;
	@Autowired
	private ProgramMentorRepository programMentorRepository;
	@Autowired
	private ProgramMentorRemovalRepository removalRepository;
	@Autowired
	private ProgramMentorInviteRepository inviteRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private MentorshipRepository mentorshipRepository;
	@Autowired
	private MeetingRepository meetingRepository;
	@Autowired
	private NotificationRepository notificationRepository;
	@Autowired
	private MenteeProgramService menteeProgramService;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private AppUser mentor;
	private AppUser otherMentor;
	private AppUser founder;
	private AppUser teammate;
	private Program program;
	private Program otherProgram;
	private Organization team;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Program Koordinatörü", UserRole.COORDINATOR, null);
		otherCoordinator = user("Diğer Koordinatör", UserRole.COORDINATOR, null);
		mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		otherMentor = user("Kemal Arslan", UserRole.MENTOR, null);
		program = program("Kuluçka 2026");
		otherProgram = program("Büyüme 2026");
		coordinatorOf(program, coordinator);
		coordinatorOf(otherProgram, otherCoordinator);
		accepted(program, mentor);
		accepted(otherProgram, mentor);
		accepted(program, otherMentor);

		team = team("Döngü", program);
		founder = user("Gülse Buran", UserRole.MENTEE, team);
		teammate = user("Mert Kaya", UserRole.MENTEE, team);
		team.setFounder(founder);
		team = organizationRepository.saveAndFlush(team);
	}

	@Test
	void theMentorLeavesThatProgramsPoolAndStaysInTheOthers() throws Exception {
		remove(admin, program, mentor)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.mentorId").value(mentor.getId().toString()))
				.andExpect(jsonPath("$.programId").value(program.getId().toString()))
				.andExpect(jsonPath("$.programName").value("Kuluçka 2026"))
				.andExpect(jsonPath("$.removedAt").exists())
				.andExpect(jsonPath("$.withdrawnRequests").value(0));

		assertThat(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId()))
				.extracting(ProgramMentor::getProgramId)
				.containsExactly(otherProgram.getId());
		// Nobody in the program can be shown, recommended or asked for this mentor any more.
		assertThat(menteeProgramService.acceptedMentorIds(program.getId())).containsExactly(otherMentor.getId());
		assertThat(menteeProgramService.acceptedMentorIds(otherProgram.getId())).containsExactly(mentor.getId());
	}

	@Test
	void recordsWhoRemovedWhichMentorFromWhichProgram() throws Exception {
		Instant joined = membership(program, mentor).getCreatedAt();

		remove(admin, program, mentor).andExpect(status().isOk());

		assertThat(removalRepository.findByMentorIdOrderByRemovedAtDesc(mentor.getId())).singleElement()
				.satisfies(audit -> {
					assertThat(audit.getProgramId()).isEqualTo(program.getId());
					assertThat(audit.getRemovedById()).isEqualTo(admin.getId());
					assertThat(audit.getRemovedAt()).isNotNull();
					assertThat(audit.getJoinedAt()).isEqualTo(joined);
				});
	}

	@Test
	void withdrawsOnlyTheUnansweredRequestsMadeInThatProgramAndTellsWhoAsked() throws Exception {
		MentorshipRequest open = request(founder, mentor, program, MentorshipRequestStatus.PENDING);
		MentorshipRequest moreInfo = request(founder, mentor, program, MentorshipRequestStatus.MORE_INFO_REQUESTED);
		MentorshipRequest countered = request(teammate, mentor, program, MentorshipRequestStatus.COUNTER_PROPOSED);
		MentorshipRequest accepted = request(founder, mentor, program, MentorshipRequestStatus.ACCEPTED);
		MentorshipRequest declined = request(founder, mentor, program, MentorshipRequestStatus.DECLINED);
		MentorshipRequest inTheOtherProgram = request(founder, mentor, otherProgram, MentorshipRequestStatus.PENDING);
		MentorshipRequest outsideAnyProgram = request(founder, mentor, null, MentorshipRequestStatus.PENDING);
		MentorshipRequest toAnotherMentor = request(founder, otherMentor, program, MentorshipRequestStatus.PENDING);

		remove(admin, program, mentor)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.withdrawnRequests").value(3));

		assertThat(statusOf(open)).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		assertThat(statusOf(moreInfo)).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		assertThat(statusOf(countered)).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		assertThat(statusOf(accepted)).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(statusOf(declined)).isEqualTo(MentorshipRequestStatus.DECLINED);
		assertThat(statusOf(inTheOtherProgram)).isEqualTo(MentorshipRequestStatus.PENDING);
		assertThat(statusOf(outsideAnyProgram)).isEqualTo(MentorshipRequestStatus.PENDING);
		assertThat(statusOf(toAnotherMentor)).isEqualTo(MentorshipRequestStatus.PENDING);
		// The history stays with the program it happened in.
		assertThat(mentorshipRequestRepository.findById(open.getId()).orElseThrow().getProgram().getId())
				.isEqualTo(program.getId());
		// One notice per person who asked, however many of their requests were waiting, and it says why.
		assertThat(notifications(founder)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Leyla Aksoy", "Kuluçka 2026");
			assertThat(notification.getHref())
					.isIn(REQUEST_HREF + open.getId(), REQUEST_HREF + moreInfo.getId());
		});
		assertThat(notifications(teammate)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Leyla Aksoy", "Kuluçka 2026");
			assertThat(notification.getHref()).isEqualTo(REQUEST_HREF + countered.getId());
		});
	}

	@Test
	void aRunningMentorshipAndItsBookedMeetingGoOnUnderTheProgram() throws Exception {
		Mentorship running = new Mentorship();
		running.setMentor(mentor);
		running.setOrganization(team);
		running.setProgram(program);
		running.setStatus(MentorshipStatus.ACTIVE);
		running.setStartDate(LocalDate.now().minusDays(1));
		running = mentorshipRepository.saveAndFlush(running);
		Meeting booked = new Meeting();
		booked.setOrganization(team);
		booked.setMentor(mentor);
		booked.setMentorshipRequest(request(founder, mentor, program, MentorshipRequestStatus.ACCEPTED));
		booked.setScheduledAt(Instant.now().plus(3, ChronoUnit.DAYS));
		booked.setDurationMinutes(60);
		booked.setStatus(MeetingStatus.SCHEDULED);
		booked = meetingRepository.saveAndFlush(booked);

		remove(admin, program, mentor)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.withdrawnRequests").value(0));

		Mentorship after = mentorshipRepository.findById(running.getId()).orElseThrow();
		assertThat(after.getStatus()).isEqualTo(MentorshipStatus.ACTIVE);
		assertThat(after.getProgram().getId()).isEqualTo(program.getId());
		assertThat(meetingRepository.findById(booked.getId()).orElseThrow().getStatus())
				.isEqualTo(MeetingStatus.SCHEDULED);
		assertThat(notifications(founder)).isEmpty();
	}

	@Test
	void tellsTheMentorAndTheProgramsCoordinatorsOnly() throws Exception {
		remove(admin, program, mentor).andExpect(status().isOk());

		assertThat(notifications(mentor)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Kuluçka 2026");
			assertThat(notification.getHref()).isEqualTo("/mentor-dashboard");
		});
		assertThat(notifications(coordinator)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Leyla Aksoy", "Kuluçka 2026");
			assertThat(notification.getHref()).isEqualTo("/coordinator/programs/" + program.getId());
		});
		assertThat(notifications(otherCoordinator)).isEmpty();
		assertThat(notifications(otherMentor)).isEmpty();
		assertThat(notifications(admin)).isEmpty();
	}

	@Test
	void anAdminWhoRunsTheProgramIsNotToldAboutTheirOwnRemoval() throws Exception {
		// A program without coordinators is run by the admin who created it.
		Program ownedByAdmin = program("Yönetici Programı");
		accepted(ownedByAdmin, mentor);

		remove(admin, ownedByAdmin, mentor).andExpect(status().isOk());

		assertThat(notifications(admin)).isEmpty();
		assertThat(notifications(mentor)).hasSize(1);
	}

	@Test
	void theMentorCanBeAddedBackLater() throws Exception {
		remove(admin, program, mentor).andExpect(status().isOk());

		mockMvc.perform(post(ASSIGN, program.getId(), mentor.getId())
						.param("actorId", admin.getId().toString())
						.with(httpBasic("test", "test")))
				.andExpect(status().isNoContent());

		assertThat(membership(program, mentor).getStatus()).isEqualTo(ProgramMentorStatus.ACCEPTED);
		assertThat(menteeProgramService.acceptedMentorIds(program.getId())).contains(mentor.getId());
		// Each removal is its own record; adding back does not erase it.
		assertThat(removalRepository.findByMentorIdOrderByRemovedAtDesc(mentor.getId())).hasSize(1);
	}

	@Test
	void theSignupLinkTheMentorJoinedWithGoesWithTheMembership() throws Exception {
		usedInvite(program, mentor);
		ProgramMentorInvite elsewhere = usedInvite(otherProgram, mentor);

		remove(admin, program, mentor).andExpect(status().isOk());

		assertThat(inviteRepository.findByProgramIdAndMentorId(program.getId(), mentor.getId())).isEmpty();
		assertThat(inviteRepository.findById(elsewhere.getId())).isPresent();
	}

	@Test
	void aMentorCanBeRemovedFromAProgramThatHasEnded() throws Exception {
		program.setStatus(ProgramStatus.COMPLETED);
		programRepository.saveAndFlush(program);

		remove(admin, program, mentor).andExpect(status().isOk());

		assertThat(programMentorRepository.findByProgramIdAndMentorId(program.getId(), mentor.getId())).isEmpty();
	}

	@Test
	void refusesAnInvitationOrARequestNobodyHasAnsweredYet() throws Exception {
		ProgramMentor invited = membership(program, mentor);
		invited.setStatus(ProgramMentorStatus.PENDING);
		programMentorRepository.saveAndFlush(invited);
		remove(admin, program, mentor).andExpect(status().isBadRequest());

		invited.setInvitedBy(ProgramMentorInvitedBy.MENTOR);
		programMentorRepository.saveAndFlush(invited);
		remove(admin, program, mentor).andExpect(status().isBadRequest());

		invited.setStatus(ProgramMentorStatus.REJECTED);
		programMentorRepository.saveAndFlush(invited);
		remove(admin, program, mentor).andExpect(status().isBadRequest());

		assertThat(programMentorRepository.findByProgramIdAndMentorId(program.getId(), mentor.getId())).isPresent();
		assertThat(removalRepository.findByMentorIdOrderByRemovedAtDesc(mentor.getId())).isEmpty();
		assertThat(notifications(mentor)).isEmpty();
	}

	@Test
	void refusesAMentorWhoIsNotInTheProgram() throws Exception {
		Program third = program("Üçüncü Program");

		remove(admin, third, mentor).andExpect(status().isBadRequest());

		assertNothingRemoved();
	}

	@Test
	void refusesAProgramOfTheOtherEdition() throws Exception {
		program.setProgramType(ProgramType.PEER_MENTORING);
		programRepository.saveAndFlush(program);

		remove(admin, program, mentor).andExpect(status().isBadRequest());

		assertNothingRemoved();
	}

	@Test
	void answersNotFoundForAnUnknownMentorOrProgram() throws Exception {
		AppUser unknownMentor = new AppUser();
		unknownMentor.setId(UUID.randomUUID());
		Program unknownProgram = new Program();
		unknownProgram.setId(UUID.randomUUID());

		remove(admin, program, unknownMentor).andExpect(status().isNotFound());
		remove(admin, unknownProgram, mentor).andExpect(status().isNotFound());

		assertNothingRemoved();
	}

	@Test
	void onlyAnAdminRemovesAMentor() throws Exception {
		remove(coordinator, program, mentor).andExpect(status().isForbidden());
		remove(mentor, program, mentor).andExpect(status().isForbidden());
		remove(founder, program, mentor).andExpect(status().isForbidden());

		assertNothingRemoved();
	}

	// A signed-in token is always the caller, whatever actorId the request names.
	@Test
	void aSignedInCoordinatorCannotActAsTheAdmin() throws Exception {
		mockMvc.perform(post(REMOVE, program.getId(), mentor.getId())
						.param("actorId", admin.getId().toString())
						.with(authentication(signedIn(coordinator))))
				.andExpect(status().isForbidden());

		assertNothingRemoved();
	}

	@Test
	void signedOutCallsAreRefused() throws Exception {
		mockMvc.perform(post(REMOVE, program.getId(), mentor.getId())
						.param("actorId", admin.getId().toString()))
				.andExpect(status().isUnauthorized());

		assertNothingRemoved();
	}

	private void assertNothingRemoved() {
		assertThat(programMentorRepository.findByMentorIdOrderByCreatedAtAsc(mentor.getId()))
				.extracting(ProgramMentor::getProgramId)
				.containsExactlyInAnyOrder(program.getId(), otherProgram.getId());
		assertThat(removalRepository.findByMentorIdOrderByRemovedAtDesc(mentor.getId())).isEmpty();
		assertThat(notifications(mentor)).isEmpty();
		assertThat(notifications(coordinator)).isEmpty();
	}

	private ResultActions remove(AppUser actor, Program from, AppUser removed) throws Exception {
		return mockMvc.perform(post(REMOVE, from.getId(), removed.getId())
				.param("actorId", actor.getId().toString())
				.with(httpBasic("test", "test")));
	}

	private List<Notification> notifications(AppUser recipient) {
		return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipient.getId());
	}

	private MentorshipRequestStatus statusOf(MentorshipRequest request) {
		return mentorshipRequestRepository.findById(request.getId()).orElseThrow().getStatus();
	}

	private ProgramMentor membership(Program of, AppUser member) {
		return programMentorRepository.findByProgramIdAndMentorId(of.getId(), member.getId()).orElseThrow();
	}

	private MentorshipRequest request(AppUser requester, AppUser to, Program in, MentorshipRequestStatus status) {
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(requester);
		request.setOrganization(team);
		request.setMentor(to);
		request.setProgram(in);
		request.setRequestedForName("Döngü");
		request.setSupportArea("Finans");
		request.setSummary("Finans");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(status);
		return mentorshipRequestRepository.saveAndFlush(request);
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@removal.test");
		user.setRole(role);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Organization team(String name, Program joined) {
		Organization organization = new Organization();
		organization.setName(name);
		Organization saved = organizationRepository.saveAndFlush(organization);
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(joined.getId());
		startup.setOrganizationId(saved.getId());
		startup.setCreatedAt(Instant.now());
		programStartupRepository.saveAndFlush(startup);
		return saved;
	}

	private Program program(String name) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		return programRepository.saveAndFlush(created);
	}

	private void accepted(Program in, AppUser member) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(in.getId());
		membership.setMentorId(member.getId());
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));
		membership.setDecidedAt(Instant.now().minus(29, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS));
		programMentorRepository.saveAndFlush(membership);
	}

	private ProgramMentorInvite usedInvite(Program in, AppUser joined) {
		ProgramMentorInvite invite = new ProgramMentorInvite();
		invite.setId(UUID.randomUUID());
		invite.setProgramId(in.getId());
		invite.setEmail(joined.getEmail());
		invite.setCode(UUID.randomUUID().toString().replace("-", ""));
		invite.setCreatedBy(coordinator.getId());
		invite.setCreatedAt(Instant.now());
		invite.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
		invite.setMentorId(joined.getId());
		return inviteRepository.saveAndFlush(invite);
	}

	private void coordinatorOf(Program coordinated, AppUser user) {
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(coordinated.getId());
		link.setUserId(user.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinatorRepository.saveAndFlush(link);
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "removal-test-uid"), null, List.of());
	}
}
