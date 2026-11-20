package com.dps.teknokent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Notification;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.entity.ProgramStartup;
import com.dps.teknokent.entity.TeamChangeRequest;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.NotificationRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramStartupRepository;
import com.dps.teknokent.repository.TeamChangeRequestRepository;
import com.dps.teknokent.security.AppUserPrincipal;

/** "Kurucuyu değiştir": who may make which member a startup's founder, and who is told. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FounderChangeIntegrationTest {

	private static final String FOUNDER = "/api/organizations/{organizationId}/founder";

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
	private TeamChangeRequestRepository teamChangeRequestRepository;
	@Autowired
	private NotificationRepository notificationRepository;

	private AppUser admin;
	private AppUser coordinator;
	private AppUser otherCoordinator;
	private Program program;
	private Organization team;
	private AppUser founder;
	private AppUser member;

	@BeforeEach
	void setUp() {
		admin = user("Admin Test", UserRole.ADMIN, null);
		coordinator = user("Koordinatör Bir", UserRole.COORDINATOR, null);
		otherCoordinator = user("Koordinatör İki", UserRole.COORDINATOR, null);
		program = program("METUStars Explore", coordinator);
		program("Kuluçka 2026", otherCoordinator);
		team = team("Yıldız Team", program);
		founder = user("Merve Geçer", UserRole.MENTEE, team);
		member = user("Zeynep Beyza Takkabulan", UserRole.MENTEE, team);
		team.setFounder(founder);
		team = organizationRepository.saveAndFlush(team);
	}

	@Test
	void aCoordinatorMakesAnotherMemberTheFounder() throws Exception {
		change(coordinator, team, member)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.organizationId").value(team.getId().toString()))
				.andExpect(jsonPath("$.previousFounderId").value(founder.getId().toString()))
				.andExpect(jsonPath("$.founderId").value(member.getId().toString()))
				.andExpect(jsonPath("$.changedAt").exists());

		Organization changed = organizationRepository.findById(team.getId()).orElseThrow();
		assertThat(changed.getFounder().getId()).isEqualTo(member.getId());
		assertThat(userRepository.findById(founder.getId()).orElseThrow().getOrganization().getId())
				.isEqualTo(team.getId());
	}

	@Test
	void tellsTheOldFounderTheNewFounderAndTheOtherCoordinators() throws Exception {
		change(admin, team, member).andExpect(status().isOk());

		assertThat(notifications(member)).singleElement().satisfies(notification -> {
			assertThat(notification.getBody()).contains("Yıldız Team");
			assertThat(notification.getHref()).isEqualTo("/startup/team");
		});
		assertThat(notifications(founder)).singleElement().satisfies(notification -> assertThat(
				notification.getBody()).contains("Yıldız Team", "Zeynep Beyza Takkabulan"));
		assertThat(notifications(coordinator)).singleElement().satisfies(notification -> assertThat(
				notification.getHref()).isEqualTo("/coordinator/programs/" + program.getId() + "/teams"));
		assertThat(notifications(admin)).isEmpty();
		assertThat(notifications(otherCoordinator)).isEmpty();
	}

	@Test
	void theCoordinatorWhoChangedItIsNotToldButTheirColleagueIs() throws Exception {
		AppUser colleague = user("Koordinatör Üç", UserRole.COORDINATOR, null);
		runs(program, colleague);

		change(coordinator, team, member).andExpect(status().isOk());

		assertThat(notifications(coordinator)).isEmpty();
		assertThat(notifications(colleague)).singleElement().satisfies(notification -> assertThat(
				notification.getBody()).contains("Yıldız Team", "Zeynep Beyza Takkabulan"));
	}

	@Test
	void aCoordinatorOutsideTheStartupsProgramsGetsANotFound() throws Exception {
		mockMvc.perform(get(FOUNDER, team.getId()).with(authentication(signedIn(otherCoordinator))))
				.andExpect(status().isNotFound());
		change(otherCoordinator, team, member).andExpect(status().isNotFound());

		assertFounderUnchanged();
	}

	@Test
	void onlyAdminsAndCoordinatorsMayChangeIt() throws Exception {
		change(founder, team, member).andExpect(status().isForbidden());
		change(member, team, member).andExpect(status().isForbidden());

		assertFounderUnchanged();
	}

	@Test
	void theSharedServiceCredentialsCannotChangeIt() throws Exception {
		mockMvc.perform(post(FOUNDER, team.getId())
				.param("actorId", admin.getId().toString())
				.with(httpBasic("test", "test"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(member)))
				.andExpect(status().isForbidden());

		assertFounderUnchanged();
	}

	@Test
	void refusesSomeoneOutsideTheTeamAPendingMemberAMentorAndTheFounderThemselves() throws Exception {
		Organization other = team("Pazar Lab", program);
		AppUser outsider = user("Ece Kurt", UserRole.MENTEE, other);
		AppUser pending = user("Bekleyen Üye", UserRole.MENTEE, team);
		pending.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(pending);
		AppUser mentor = user("Leyla Aksoy", UserRole.MENTOR, team);

		change(admin, team, outsider).andExpect(status().isBadRequest());
		change(admin, team, pending).andExpect(status().isBadRequest());
		change(admin, team, mentor).andExpect(status().isBadRequest());
		change(admin, team, founder).andExpect(status().isBadRequest());
		mockMvc.perform(post(FOUNDER, team.getId())
				.with(authentication(signedIn(admin)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"newFounderId\":\"" + UUID.randomUUID() + "\"}"))
				.andExpect(status().isBadRequest());

		assertFounderUnchanged();
	}

	@Test
	void refusesAMemberWhoIsWaitingToLeave() throws Exception {
		TeamChangeRequest leave = new TeamChangeRequest();
		leave.setOrganization(team);
		leave.setProgramId(program.getId());
		leave.setMember(member);
		leave.setRequestedBy(member);
		leave.setKind(TeamChangeKind.LEAVE);
		leave.setStatus(TeamChangeStatus.PENDING);
		teamChangeRequestRepository.saveAndFlush(leave);

		change(admin, team, member).andExpect(status().isBadRequest());

		assertFounderUnchanged();
	}

	@Test
	void refusesAnArchivedStartupAndOneThatDoesNotExist() throws Exception {
		team.setArchivedAt(Instant.now());
		organizationRepository.saveAndFlush(team);

		change(admin, team, member).andExpect(status().isBadRequest());
		mockMvc.perform(post(FOUNDER, UUID.randomUUID())
				.with(authentication(signedIn(admin)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(member)))
				.andExpect(status().isNotFound());
	}

	@Test
	void refusesABodyWithoutTheNewFounder() throws Exception {
		mockMvc.perform(post(FOUNDER, team.getId())
				.with(authentication(signedIn(admin)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void listsTheFounderAndTheApprovedMembersWhoMayTakeOver() throws Exception {
		AppUser pending = user("Bekleyen Üye", UserRole.MENTEE, team);
		pending.setStatus(UserAccountStatus.PENDING);
		userRepository.saveAndFlush(pending);
		user("Ayşe Çelik", UserRole.MENTEE, team);

		mockMvc.perform(get(FOUNDER, team.getId()).with(authentication(signedIn(coordinator))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Yıldız Team"))
				.andExpect(jsonPath("$.currentFounder.id").value(founder.getId().toString()))
				.andExpect(jsonPath("$.currentFounder.email").doesNotExist())
				.andExpect(jsonPath("$.candidates.length()").value(2))
				.andExpect(jsonPath("$.candidates[0].fullName").value("Ayşe Çelik"))
				.andExpect(jsonPath("$.candidates[1].fullName").value("Zeynep Beyza Takkabulan"));
	}

	@Test
	void anArchivedStartupOffersNobody() throws Exception {
		team.setArchivedAt(Instant.now());
		organizationRepository.saveAndFlush(team);

		mockMvc.perform(get(FOUNDER, team.getId()).with(authentication(signedIn(admin))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.candidates").isEmpty());
	}

	private void assertFounderUnchanged() {
		assertThat(organizationRepository.findById(team.getId()).orElseThrow().getFounder().getId())
				.isEqualTo(founder.getId());
	}

	private ResultActions change(AppUser actor, Organization startup, AppUser newFounder) throws Exception {
		return mockMvc.perform(post(FOUNDER, startup.getId())
				.with(authentication(signedIn(actor)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(newFounder)));
	}

	private static String body(AppUser newFounder) {
		return "{\"newFounderId\":\"" + newFounder.getId() + "\"}";
	}

	private List<Notification> notifications(AppUser recipient) {
		return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipient.getId());
	}

	private AppUser user(String name, UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@founder-change.test");
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

	private Program program(String name, AppUser runBy) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName(name);
		created.setCreatedBy(admin.getId());
		created.setCreatedAt(Instant.now());
		created.setStatus(ProgramStatus.ACTIVE);
		Program saved = programRepository.saveAndFlush(created);
		runs(saved, runBy);
		return saved;
	}

	private void runs(Program coordinated, AppUser runBy) {
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(coordinated.getId());
		link.setUserId(runBy.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinatorRepository.saveAndFlush(link);
	}

	private static UsernamePasswordAuthenticationToken signedIn(AppUser user) {
		return new UsernamePasswordAuthenticationToken(new AppUserPrincipal(
				user.getId(), user.getEmail(), user.getRole(), user.getStatus(), "founder-change-uid"), null,
				List.of());
	}
}
