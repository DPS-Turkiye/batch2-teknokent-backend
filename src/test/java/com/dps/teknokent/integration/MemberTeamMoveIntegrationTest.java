package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.OrganizationPendingMember;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamChangeRequest;
import com.dps.teknokent.entity.TeamMembership;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.OrganizationPendingMemberRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.repository.TeamMembershipRepository;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.service.TeamMembershipService;

/** Moving one person to another team: who may, who may not, and what goes with them. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MemberTeamMoveIntegrationTest {

	private static final String MOVE = "/api/users/{userId}/team-move";

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
	private TeamMembershipRepository teamMembershipRepository;
	@Autowired
	private TeamMembershipService teamMembershipService;
	@Autowired
	private TeamChangeRequestRepository teamChangeRequestRepository;
	@Autowired
	private MentorshipRequestRepository mentorshipRequestRepository;
	@Autowired
	private NotificationRepository notificationRepository;
	@Autowired
	private OrganizationPendingMemberRepository pendingMemberRepository;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private Program program;
	private Program secondProgram;
	private Program othersProgram;
	private Organization oldTeam;
	private Organization newTeam;
	private Organization othersTeam;
	private AppUser oldFounder;
	private AppUser newFounder;
	private AppUser member;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Koordinatör Bir", UserRole.COORDINATOR, null);
		otherCoordinator = user("Koordinatör İki", UserRole.COORDINATOR, null);
		program = program("Kuluçka 2026", coordinator);
		secondProgram = program("Hızlandırma 2026", coordinator);
		othersProgram = program("Ön Kuluçka 2026", otherCoordinator);

		oldTeam = team("Döngü", program);
		newTeam = team("Pazar Lab", secondProgram);
		othersTeam = team("Rüzgar", othersProgram);
		oldFounder = founderOf(oldTeam, "Gülse Buran");
		newFounder = founderOf(newTeam, "Ceren Aksoy");
		founderOf(othersTeam, "Ece Kurt");
		member = user("Mert Kaya", UserRole.MENTEE, oldTeam);
		member.setProgramId(program.getId());
		member = userRepository.saveAndFlush(member);
		teamMembershipService.joined(member, oldTeam);
	}

	@Test
	void anAdminMovesTheMemberAndTheirProgramFollowsTheTeam() throws Exception {
		move(admin, member, newTeam)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(member.getId().toString()))
				.andExpect(jsonPath("$.from.organizationId").value(oldTeam.getId().toString()))
				.andExpect(jsonPath("$.from.programName").value("Kuluçka 2026"))
				.andExpect(jsonPath("$.to.organizationId").value(newTeam.getId().toString()))
				.andExpect(jsonPath("$.to.programName").value("Hızlandırma 2026"))
				.andExpect(jsonPath("$.programId").value(secondProgram.getId().toString()))
				.andExpect(jsonPath("$.movedAt").exists());

		AppUser moved = userRepository.findById(member.getId()).orElseThrow();
		assertThat(moved.getOrganization().getId()).isEqualTo(newTeam.getId());
		assertThat(moved.getProgramId()).isEqualTo(secondProgram.getId());
	}

	@Test
	void theTeamHistoryClosesTheOldStayAsMovedAndOpensANewOne() throws Exception {
		move(admin, member, newTeam).andExpect(status().isOk());

		List<TeamMembership> stays = teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(member.getId());
		assertThat(stays).hasSize(2);
		assertThat(stays).filteredOn(stay -> stay.getOrganization().getId().equals(oldTeam.getId()))
				.singleElement().satisfies(stay -> {
					assertThat(stay.getLeftAt()).isNotNull();
					assertThat(stay.getExitReason()).isEqualTo(TeamExitReason.MOVED);
				});
		assertThat(stays).filteredOn(stay -> stay.getOrganization().getId().equals(newTeam.getId()))
				.singleElement().satisfies(stay -> {
					assertThat(stay.getLeftAt()).isNull();
					assertThat(stay.getExitReason()).isNull();
				});
	}

	@Test
	void tellsThePersonAndBothFounders() throws Exception {
		move(admin, member, newTeam).andExpect(status().isOk());

		assertThat(notifications(member)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Döngü", "Pazar Lab");
			assertThat(notification.getHref()).isEqualTo("/startup/team");
		});
		assertThat(notifications(oldFounder)).singleElement().satisfies(notification -> {
			assertThat(notification.getTitle()).contains("Mert Kaya");
			assertThat(notification.getBody()).contains("Mert Kaya", "Döngü", "Pazar Lab");
		});
		assertThat(notifications(newFounder)).singleElement().satisfies(notification -> {
			assertThat(notification.getTitle()).contains("Mert Kaya");
			assertThat(notification.getHref()).isEqualTo("/startup/team");
		});
		assertThat(notifications(admin)).isEmpty();
	}

	@Test
	void theOldTeamKeepsItsRequestsAndTheWaitingLeaveIsCancelled() throws Exception {
		MentorshipRequest request = openRequest();
		TeamChangeRequest leave = waitingLeave();

		move(admin, member, newTeam).andExpect(status().isOk());

		MentorshipRequest kept = mentorshipRequestRepository.findById(request.getId()).orElseThrow();
		assertThat(kept.getOrganization().getId()).isEqualTo(oldTeam.getId());
		assertThat(kept.getStatus()).isEqualTo(MentorshipRequestStatus.PENDING);
		TeamChangeRequest cancelled = teamChangeRequestRepository.findById(leave.getId()).orElseThrow();
		assertThat(cancelled.getStatus()).isEqualTo(TeamChangeStatus.CANCELLED);
		assertThat(cancelled.getDecidedAt()).isNotNull();
	}

	@Test
	void someoneWhoSignedUpIntoAnUnrelatedProgramKeepsIt() throws Exception {
		member.setProgramId(othersProgram.getId());
		userRepository.saveAndFlush(member);

		move(admin, member, newTeam).andExpect(status().isOk());

		assertThat(userRepository.findById(member.getId()).orElseThrow().getProgramId())
				.isEqualTo(othersProgram.getId());
	}

	@Test
	void theTargetTeamsWaitingRowForTheSameAddressIsCleared() throws Exception {
		OrganizationPendingMember pending = new OrganizationPendingMember();
		pending.setId(UUID.randomUUID());
		pending.setOrganizationId(newTeam.getId());
		pending.setFullName("Mert Kaya");
		pending.setEmail(member.getEmail().toUpperCase(Locale.ROOT));
		pending.setCreatedAt(Instant.now());
		pendingMemberRepository.saveAndFlush(pending);

		move(admin, member, newTeam).andExpect(status().isOk());

		assertThat(pendingMemberRepository.findByOrganizationIdOrderByCreatedAtAsc(newTeam.getId())).isEmpty();
	}

	@Test
	void aCoordinatorMovesBetweenTwoProgramsTheyRun() throws Exception {
		move(coordinator, member, newTeam).andExpect(status().isOk());

		assertThat(userRepository.findById(member.getId()).orElseThrow().getOrganization().getId())
				.isEqualTo(newTeam.getId());
	}

	@Test
	void aCoordinatorCannotMoveIntoATeamOutsideTheirPrograms() throws Exception {
		move(coordinator, member, othersTeam).andExpect(status().isNotFound());

		assertStillInOldTeam();
	}

	@Test
	void aCoordinatorCannotReachSomeoneWhoseTeamIsOutsideTheirPrograms() throws Exception {
		mockMvc.perform(get(MOVE, member.getId()).with(authentication(signedIn(otherCoordinator))))
				.andExpect(status().isNotFound());
		move(otherCoordinator, member, othersTeam).andExpect(status().isNotFound());

		assertStillInOldTeam();
	}

	@Test
	void theFounderIsRefusedWithAConflict() throws Exception {
		move(admin, oldFounder, newTeam)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("TEAM_FOUNDER"))
				.andExpect(jsonPath("$.message").value(containsString("kurucu")));

		assertThat(userRepository.findById(oldFounder.getId()).orElseThrow().getOrganization().getId())
				.isEqualTo(oldTeam.getId());
	}

	@Test
	void refusesTheTeamThePersonIsAlreadyIn() throws Exception {
		move(admin, member, oldTeam).andExpect(status().isBadRequest());
	}

	@Test
	void refusesAnArchivedOrUnapprovedTeam() throws Exception {
		Organization archived = team("Eski Takım", secondProgram);
		archived.setArchivedAt(Instant.now());
		organizationRepository.saveAndFlush(archived);
		Organization waiting = team("Yeni Kayıt", secondProgram);
		waiting.setApprovalStatus(UserAccountStatus.PENDING);
		organizationRepository.saveAndFlush(waiting);

		move(admin, member, archived).andExpect(status().isBadRequest());
		move(coordinator, member, waiting).andExpect(status().isBadRequest());

		assertStillInOldTeam();
	}

	@Test
	void refusesATeamThatDoesNotExist() throws Exception {
		mockMvc.perform(post(MOVE, member.getId())
				.with(authentication(signedIn(admin)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetOrganizationId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isNotFound());
	}

	@Test
	void refusesMentorsAndPeopleWithoutATeam() throws Exception {
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, oldTeam);
		AppUser loner = user("Selin Ay", UserRole.MENTEE, null);

		move(admin, mentor, newTeam).andExpect(status().isBadRequest());
		move(admin, loner, newTeam).andExpect(status().isBadRequest());
		mockMvc.perform(get(MOVE, loner.getId()).with(authentication(signedIn(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.blockedBy").value("NO_TEAM"))
				.andExpect(jsonPath("$.currentTeam").doesNotExist())
				.andExpect(jsonPath("$.targets").isEmpty());
	}

	@Test
	void refusesAnAccountThatIsNotApprovedYet() throws Exception {
		member.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(member);

		move(admin, member, newTeam).andExpect(status().isBadRequest());
	}

	@Test
	void onlyAdminsAndCoordinatorsMayMovePeople() throws Exception {
		move(newFounder, member, newTeam).andExpect(status().isForbidden());

		assertStillInOldTeam();
	}

	@Test
	void theSharedServiceCredentialsCannotMoveAnyone() throws Exception {
		mockMvc.perform(post(MOVE, member.getId())
				.param("actorId", admin.getId().toString())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(newTeam)))
				.andExpect(status().isForbidden());
		mockMvc.perform(post(MOVE, member.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(newTeam)))
				.andExpect(status().isUnauthorized());

		assertStillInOldTeam();
	}

	@Test
	void refusesABodyWithoutATargetTeam() throws Exception {
		mockMvc.perform(post(MOVE, member.getId())
				.with(authentication(signedIn(admin)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void aCoordinatorIsOfferedOnlyOpenTeamsInTheirOwnPrograms() throws Exception {
		Organization sibling = team("Ağaç", program);
		founderOf(sibling, "Bora Er");
		Organization archived = team("Eski Takım", secondProgram);
		archived.setArchivedAt(Instant.now());
		organizationRepository.saveAndFlush(archived);

		mockMvc.perform(get(MOVE, member.getId()).with(authentication(signedIn(coordinator))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Mert Kaya"))
				.andExpect(jsonPath("$.currentTeam.name").value("Döngü"))
				.andExpect(jsonPath("$.currentTeam.programName").value("Kuluçka 2026"))
				.andExpect(jsonPath("$.blockedBy").doesNotExist())
				.andExpect(jsonPath("$.targets.length()").value(2))
				.andExpect(jsonPath("$.targets[0].name").value("Ağaç"))
				.andExpect(jsonPath("$.targets[1].name").value("Pazar Lab"))
				.andExpect(jsonPath("$.targets[1].programName").value("Hızlandırma 2026"));
	}

	@Test
	void anAdminIsOfferedOpenTeamsInEveryProgram() throws Exception {
		mockMvc.perform(get(MOVE, member.getId()).with(authentication(signedIn(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.targets[?(@.name == 'Pazar Lab')]").exists())
				.andExpect(jsonPath("$.targets[?(@.name == 'Rüzgar')].programName").value("Ön Kuluçka 2026"))
				.andExpect(jsonPath("$.targets[?(@.name == 'Döngü')]").doesNotExist());
	}

	@Test
	void theFounderIsShownWhyTheyCannotMove() throws Exception {
		mockMvc.perform(get(MOVE, oldFounder.getId()).with(authentication(signedIn(coordinator))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.blockedBy").value("FOUNDER"))
				.andExpect(jsonPath("$.currentTeam.name").value("Döngü"))
				.andExpect(jsonPath("$.targets").isEmpty());
	}

	private void assertStillInOldTeam() {
		AppUser unchanged = userRepository.findById(member.getId()).orElseThrow();
		assertThat(unchanged.getOrganization().getId()).isEqualTo(oldTeam.getId());
		assertThat(unchanged.getProgramId()).isEqualTo(program.getId());
		assertThat(teamMembershipRepository.findByUserIdOrderByJoinedAtDesc(member.getId()))
				.singleElement().satisfies(stay -> assertThat(stay.getLeftAt()).isNull());
	}

	private ResultActions move(AppUser actor, AppUser person, Organization target) throws Exception {
		return mockMvc.perform(post(MOVE, person.getId())
				.with(authentication(signedIn(actor)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(target)));
	}

	private static String body(Organization target) {
		return "{\"targetOrganizationId\":\"" + target.getId() + "\"}";
	}

	private List<Notification> notifications(AppUser recipient) {
		return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipient.getId());
	}

	private MentorshipRequest openRequest() {
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, null);
		MentorshipRequest request = new MentorshipRequest();
		request.setRequester(member);
		request.setOrganization(oldTeam);
		request.setMentor(mentor);
		request.setProgram(program);
		request.setRequestedForName("Döngü");
		request.setSupportArea("Finans");
		request.setSummary("Finans");
		request.setUrgency(MentorshipRequestUrgency.THIS_WEEK);
		request.setMentorshipFormat(MentorshipRequestFormat.SINGLE_MEETING);
		request.setMeetingMode(MeetingMode.ONLINE);
		request.setStatus(MentorshipRequestStatus.PENDING);
		return mentorshipRequestRepository.saveAndFlush(request);
	}

	private TeamChangeRequest waitingLeave() {
		TeamChangeRequest request = new TeamChangeRequest();
		request.setOrganization(oldTeam);
		request.setProgramId(program.getId());
		request.setMember(member);
		request.setRequestedBy(member);
		request.setKind(TeamChangeKind.LEAVE);
		request.setStatus(TeamChangeStatus.PENDING);
		return teamChangeRequestRepository.saveAndFlush(request);
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@member-move.test");
		user.setRole(role);
		user.setOrganization(organization);
		return userRepository.saveAndFlush(user);
	}

	private AppUser founderOf(Organization team, String name) {
		AppUser founder = user(name, UserRole.MENTEE, team);
		team.setFounder(founder);
		organizationRepository.saveAndFlush(team);
		return founder;
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

	private Program program(String name, AppUser runBy) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		Program saved = programRepository.saveAndFlush(created);
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(saved.getId());
		link.setUserId(runBy.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinatorRepository.saveAndFlush(link);
		return saved;
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "member-move-uid"), null, List.of());
	}
}
