package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamChangeRequest;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.ProgramTeamMoveRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.security.AppUserPrincipal;

/** An admin moves a team from one program to another: the membership moves, the old program's activity stays. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProgramTeamMoveIntegrationTest {

	private static final String MOVE = "/api/admin/programs/{programId}/startups/{organizationId}/move";

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
	private ProgramTeamMoveRepository moveRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private TeamChangeRequestRepository teamChangeRequestRepository;
	@Autowired
	private NotificationRepository notificationRepository;

	private AppUser admin;
	private AppUser oldCoordinator;
	private AppUser newCoordinator;
	private AppUser founder;
	private AppUser teammate;
	private AppUser mentor;
	private Program oldProgram;
	private Program newProgram;
	private Organization team;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		oldCoordinator = user("Eski Koordinatör", UserRole.COORDINATOR, null);
		newCoordinator = user("Yeni Koordinatör", UserRole.COORDINATOR, null);
		mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		oldProgram = program("Ön Kuluçka 2026");
		newProgram = program("Kuluçka 2026");
		coordinatorOf(oldProgram, oldCoordinator);
		coordinatorOf(newProgram, newCoordinator);

		team = team("Döngü", oldProgram);
		team.setActiveProgramId(oldProgram.getId());
		team.setInviteCode("DONGU-" + UUID.randomUUID());
		team.setProblem("Atık takibi elle yapılıyor.");
		founder = user("Gülse Buran", UserRole.MENTEE, team);
		founder.setProgramId(oldProgram.getId());
		founder = userRepository.saveAndFlush(founder);
		teammate = user("Mert Kaya", UserRole.MENTEE, team);
		team.setFounder(founder);
		team = organizationRepository.saveAndFlush(team);
	}

	@Test
	void movesTheMembershipAndKeepsWhatTheTeamEntered() throws Exception {
		move(admin, newProgram, team, oldProgram)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.organizationId").value(team.getId().toString()))
				.andExpect(jsonPath("$.fromProgramId").value(oldProgram.getId().toString()))
				.andExpect(jsonPath("$.fromProgramName").value("Ön Kuluçka 2026"))
				.andExpect(jsonPath("$.toProgramId").value(newProgram.getId().toString()))
				.andExpect(jsonPath("$.toProgramName").value("Kuluçka 2026"))
				.andExpect(jsonPath("$.movedAt").exists())
				.andExpect(jsonPath("$.withdrawnRequests").value(0));

		assertThat(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(team.getId()))
				.extracting(ProgramStartup::getProgramId)
				.containsExactly(newProgram.getId());
		Organization moved = organizationRepository.findById(team.getId()).orElseThrow();
		assertThat(moved.getActiveProgramId()).isEqualTo(newProgram.getId());
		assertThat(moved.getInviteCode()).isEqualTo(team.getInviteCode());
		assertThat(moved.getProblem()).isEqualTo("Atık takibi elle yapılıyor.");
		assertThat(moved.getApprovalStatus()).isEqualTo(UserAccountStatus.APPROVED);
		assertThat(userRepository.findById(founder.getId()).orElseThrow().getProgramId())
				.isEqualTo(newProgram.getId());
		assertThat(userRepository.findById(teammate.getId()).orElseThrow().getOrganization().getId())
				.isEqualTo(team.getId());
	}

	@Test
	void recordsWhoMovedWhichTeamFromWhereToWhere() throws Exception {
		Instant joined = programStartupRepository
				.findByProgramIdAndOrganizationId(oldProgram.getId(), team.getId()).orElseThrow().getCreatedAt();

		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(moveRepository.findByOrganizationIdOrderByMovedAtDesc(team.getId())).singleElement()
				.satisfies(audit -> {
					assertThat(audit.getFromProgramId()).isEqualTo(oldProgram.getId());
					assertThat(audit.getToProgramId()).isEqualTo(newProgram.getId());
					assertThat(audit.getMovedById()).isEqualTo(admin.getId());
					assertThat(audit.getMovedAt()).isNotNull();
					assertThat(audit.getJoinedFromAt()).isEqualTo(joined);
				});
	}

	@Test
	void tellsBothProgramsCoordinatorsAndEveryMember() throws Exception {
		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(notifications(oldCoordinator)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Döngü", "Ön Kuluçka 2026", "Kuluçka 2026");
			assertThat(notification.getHref()).isEqualTo("/coordinator/programs/" + oldProgram.getId());
		});
		assertThat(notifications(newCoordinator)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Döngü", "Ön Kuluçka 2026", "Kuluçka 2026");
			assertThat(notification.getHref()).isEqualTo("/coordinator/programs/" + newProgram.getId());
		});
		assertThat(notifications(founder)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Ön Kuluçka 2026", "Kuluçka 2026");
			assertThat(notification.getHref()).isEqualTo("/startup");
		});
		assertThat(notifications(teammate)).hasSize(1);
		assertThat(notifications(admin)).isEmpty();
	}

	@Test
	void someoneWhoRunsBothProgramsIsToldOnce() throws Exception {
		coordinatorOf(newProgram, oldCoordinator);

		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(notifications(oldCoordinator)).singleElement().satisfies(notification -> assertThat(
				notification.getHref()).isEqualTo("/coordinator/programs/" + newProgram.getId()));
	}

	@Test
	void withdrawsOnlyTheOpenRequestsOfTheOldProgramAndTellsTheirMentor() throws Exception {
		MentorshipRequest open = request(oldProgram, MentorshipRequestStatus.PENDING);
		MentorshipRequest countered = request(oldProgram, MentorshipRequestStatus.COUNTER_PROPOSED);
		MentorshipRequest accepted = request(oldProgram, MentorshipRequestStatus.ACCEPTED);
		MentorshipRequest outsideAnyProgram = request(null, MentorshipRequestStatus.PENDING);

		move(admin, newProgram, team, oldProgram)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.withdrawnRequests").value(2));

		assertThat(statusOf(open)).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		assertThat(statusOf(countered)).isEqualTo(MentorshipRequestStatus.WITHDRAWN);
		assertThat(statusOf(accepted)).isEqualTo(MentorshipRequestStatus.ACCEPTED);
		assertThat(statusOf(outsideAnyProgram)).isEqualTo(MentorshipRequestStatus.PENDING);
		// Everything stays with the program it happened in.
		assertThat(mentorshipRequestRepository.findById(accepted.getId()).orElseThrow().getProgram().getId())
				.isEqualTo(oldProgram.getId());
		assertThat(mentorshipRequestRepository.findById(open.getId()).orElseThrow().getProgram().getId())
				.isEqualTo(oldProgram.getId());
		// One notice for the mentor, however many of the team's requests were waiting on them.
		assertThat(notifications(mentor)).singleElement()
				.satisfies(notification -> assertThat(notification.getBody()).contains("Döngü"));
	}

	@Test
	void aWaitingTeamChangeGoesToTheNewProgramsCoordinators() throws Exception {
		TeamChangeRequest waiting = teamChange(TeamChangeStatus.PENDING);
		TeamChangeRequest decided = teamChange(TeamChangeStatus.APPROVED);

		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(teamChangeRequestRepository.findById(waiting.getId()).orElseThrow().getProgramId())
				.isEqualTo(newProgram.getId());
		assertThat(teamChangeRequestRepository.findById(decided.getId()).orElseThrow().getProgramId())
				.isEqualTo(oldProgram.getId());
	}

	@Test
	void aTeamListedInBothProgramsEndsUpOnlyInTheTarget() throws Exception {
		join(team, newProgram);

		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(team.getId()))
				.extracting(ProgramStartup::getProgramId)
				.containsExactly(newProgram.getId());
		assertThat(organizationRepository.findById(team.getId()).orElseThrow().getActiveProgramId())
				.isEqualTo(newProgram.getId());
	}

	@Test
	void theOnlyTeamOfAProgramCanLeaveIt() throws Exception {
		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(programStartupRepository.findByProgramIdOrderByCreatedAtAsc(oldProgram.getId())).isEmpty();
	}

	@Test
	void aTeamCanLeaveAProgramThatHasEnded() throws Exception {
		oldProgram.setStatus(ProgramStatus.COMPLETED);
		programRepository.saveAndFlush(oldProgram);

		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());
	}

	@Test
	void aTeamStillWaitingForApprovalIsApprovedByTheMove() throws Exception {
		team.setApprovalStatus(UserAccountStatus.PENDING);
		organizationRepository.saveAndFlush(team);

		move(admin, newProgram, team, oldProgram).andExpect(status().isOk());

		assertThat(organizationRepository.findById(team.getId()).orElseThrow().getApprovalStatus())
				.isEqualTo(UserAccountStatus.APPROVED);
	}

	@Test
	void aMemberWhoIsNotEligibleStopsTheWholeMove() throws Exception {
		newProgram.setEligibleEmailDomain("metu.edu.tr");
		programRepository.saveAndFlush(newProgram);

		move(admin, newProgram, team, oldProgram).andExpect(status().isBadRequest());

		assertNothingMoved();
	}

	@Test
	void refusesATargetThatIsNotOpen() throws Exception {
		newProgram.setStatus(ProgramStatus.COMPLETED);
		programRepository.saveAndFlush(newProgram);
		move(admin, newProgram, team, oldProgram).andExpect(status().isBadRequest());

		newProgram.setStatus(ProgramStatus.DRAFT);
		programRepository.saveAndFlush(newProgram);
		move(admin, newProgram, team, oldProgram).andExpect(status().isBadRequest());

		assertNothingMoved();
	}

	@Test
	void refusesAProgramOfTheOtherEdition() throws Exception {
		newProgram.setProgramType(ProgramType.PEER_MENTORING);
		programRepository.saveAndFlush(newProgram);

		move(admin, newProgram, team, oldProgram).andExpect(status().isBadRequest());

		assertNothingMoved();
	}

	@Test
	void refusesTheSameProgramAndAProgramTheTeamIsNotIn() throws Exception {
		Program third = program("Üçüncü Program");

		move(admin, oldProgram, team, oldProgram).andExpect(status().isBadRequest());
		move(admin, newProgram, team, third).andExpect(status().isBadRequest());

		assertNothingMoved();
	}

	@Test
	void refusesAPersonalOrAnArchivedTeam() throws Exception {
		team.setPersonal(true);
		organizationRepository.saveAndFlush(team);
		move(admin, newProgram, team, oldProgram).andExpect(status().isBadRequest());

		team.setPersonal(false);
		team.setArchivedAt(Instant.now());
		organizationRepository.saveAndFlush(team);
		move(admin, newProgram, team, oldProgram).andExpect(status().isBadRequest());

		assertNothingMoved();
	}

	@Test
	void answersNotFoundForAnUnknownTeamOrProgram() throws Exception {
		Organization unknownTeam = new Organization();
		unknownTeam.setId(UUID.randomUUID());
		Program unknownProgram = new Program();
		unknownProgram.setId(UUID.randomUUID());

		move(admin, newProgram, unknownTeam, oldProgram).andExpect(status().isNotFound());
		move(admin, unknownProgram, team, oldProgram).andExpect(status().isNotFound());

		assertNothingMoved();
	}

	@Test
	void onlyAnAdminMovesATeam() throws Exception {
		move(oldCoordinator, newProgram, team, oldProgram).andExpect(status().isForbidden());
		move(newCoordinator, newProgram, team, oldProgram).andExpect(status().isForbidden());
		move(founder, newProgram, team, oldProgram).andExpect(status().isForbidden());

		assertNothingMoved();
	}

	// A signed-in token is always the caller, whatever actorId the request names.
	@Test
	void aSignedInCoordinatorCannotActAsTheAdmin() throws Exception {
		mockMvc.perform(post(MOVE, newProgram.getId(), team.getId())
						.param("actorId", admin.getId().toString())
						.with(authentication(signedIn(oldCoordinator)))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(oldProgram)))
				.andExpect(status().isForbidden());

		assertNothingMoved();
	}

	@Test
	void signedOutCallsAreRefused() throws Exception {
		mockMvc.perform(post(MOVE, newProgram.getId(), team.getId())
						.param("actorId", admin.getId().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(oldProgram)))
				.andExpect(status().isUnauthorized());

		assertNothingMoved();
	}

	@Test
	void refusesABodyWithoutTheProgramToLeave() throws Exception {
		mockMvc.perform(post(MOVE, newProgram.getId(), team.getId())
						.param("actorId", admin.getId().toString())
						.with(httpBasic("test", "test"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());

		assertNothingMoved();
	}

	private void assertNothingMoved() {
		assertThat(programStartupRepository.findByOrganizationIdOrderByCreatedAtAsc(team.getId()))
				.extracting(ProgramStartup::getProgramId)
				.containsExactly(oldProgram.getId());
		assertThat(organizationRepository.findById(team.getId()).orElseThrow().getActiveProgramId())
				.isEqualTo(oldProgram.getId());
		assertThat(moveRepository.findByOrganizationIdOrderByMovedAtDesc(team.getId())).isEmpty();
		assertThat(notifications(founder)).isEmpty();
	}

	private ResultActions move(AppUser actor, Program target, Organization moved, Program from) throws Exception {
		return mockMvc.perform(post(MOVE, target.getId(), moved.getId())
				.param("actorId", actor.getId().toString())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(from)));
	}

	private static String body(Program from) {
		return "{\"fromProgramId\":\"" + from.getId() + "\"}";
	}

	private List<Notification> notifications(AppUser recipient) {
		return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipient.getId());
	}

	private MentorshipRequestStatus statusOf(MentorshipRequest request) {
		return mentorshipRequestRepository.findById(request.getId()).orElseThrow().getStatus();
	}

	private MentorshipRequest request(Program program, MentorshipRequestStatus status) {
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
		return mentorshipRequestRepository.saveAndFlush(request);
	}

	private TeamChangeRequest teamChange(TeamChangeStatus status) {
		TeamChangeRequest request = new TeamChangeRequest();
		request.setOrganization(team);
		request.setProgramId(oldProgram.getId());
		request.setMember(teammate);
		request.setRequestedBy(teammate);
		request.setKind(TeamChangeKind.LEAVE);
		request.setStatus(status);
		return teamChangeRequestRepository.saveAndFlush(request);
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@move.test");
		user.setRole(role);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private Organization team(String name, Program joined) {
		Organization organization = new Organization();
		organization.setName(name);
		Organization saved = organizationRepository.saveAndFlush(organization);
		join(saved, joined);
		return saved;
	}

	private void join(Organization organization, Program joined) {
		ProgramStartup startup = new ProgramStartup();
		startup.setId(UUID.randomUUID());
		startup.setProgramId(joined.getId());
		startup.setOrganizationId(organization.getId());
		startup.setCreatedAt(Instant.now());
		programStartupRepository.saveAndFlush(startup);
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
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "move-test-uid"), null, List.of());
	}
}
