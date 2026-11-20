package com.dps.teknokent.integration;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.ProgramType;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramTeamInvite;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.OrganizationRepository;
import com.dps.teknokent.repository.ProgramRepository;
import com.dps.teknokent.repository.ProgramTeamInviteRepository;
import com.dps.teknokent.util.ClientAddressKey;

/** /invite's check of a team code before any login is made: valid or not, and the team's name, nothing more. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class InviteCodeCheckIntegrationTest {

	private static final String CHECK = "/api/auth/invite-code/check";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private OrganizationRepository organizationRepository;
	@Autowired
	private AppUserRepository userRepository;
	@Autowired
	private ProgramTeamInviteRepository programTeamInviteRepository;
	@Autowired
	private ProgramRepository programRepository;

	private Organization team;
	private int visitor;

	@BeforeEach
	void setUp() {
		team = team("Yıldız Team", "YLDZ2345");
		AppUser founder = new AppUser();
		founder.setFullName("Merve Geçer");
		founder.setEmail(UUID.randomUUID() + "@code-check.test");
		founder.setRole(UserRole.MENTEE);
		founder.setStatus(UserAccountStatus.APPROVED);
		founder.setOrganization(team);
		founder = userRepository.saveAndFlush(founder);
		team.setFounder(founder);
		team = organizationRepository.saveAndFlush(team);
	}

	@Test
	void aLiveStartupsCodeAnswersOnlyValidAndTheTeamName() throws Exception {
		check("YLDZ2345")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", aMapWithSize(2)))
				.andExpect(jsonPath("$.valid").value(true))
				.andExpect(jsonPath("$.teamName").value("Yıldız Team"));
	}

	@Test
	void foldsCaseAndSpacesLikeRegistrationDoes() throws Exception {
		check("  yldz2345 ")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valid").value(true))
				.andExpect(jsonPath("$.teamName").value("Yıldız Team"));
	}

	@Test
	void anUnknownCodeIsNotValidAndNamesNoTeam() throws Exception {
		check("NOPE2345")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.valid").value(false))
				.andExpect(jsonPath("$.teamName").doesNotExist());
	}

	@Test
	void anArchivedStartupsCodeIsNotValid() throws Exception {
		team.setArchivedAt(Instant.now());
		organizationRepository.saveAndFlush(team);

		check("YLDZ2345").andExpect(jsonPath("$.valid").value(false));
	}

	@Test
	void aProgramTeamInviteWhoseLeadHasNotJoinedIsValid() throws Exception {
		invite("Ay Team", "AYTM2345", null, program(ProgramStatus.ACTIVE, ProgramType.STARTUP_MENTORING));

		check("AYTM2345")
				.andExpect(jsonPath("$.valid").value(true))
				.andExpect(jsonPath("$.teamName").value("Ay Team"));
	}

	@Test
	void aDraftOrOtherEditionProgramsTeamInviteOpensNothingYet() throws Exception {
		invite("Taslak Team", "TSLK2345", null, program(ProgramStatus.DRAFT, ProgramType.STARTUP_MENTORING));
		invite("Akran Team", "AKRN2345", null, program(ProgramStatus.ACTIVE, ProgramType.PEER_MENTORING));

		check("TSLK2345").andExpect(jsonPath("$.valid").value(false))
				.andExpect(jsonPath("$.teamName").doesNotExist());
		check("AKRN2345").andExpect(jsonPath("$.valid").value(false));
	}

	@Test
	void aClaimedProgramTeamInviteAnswersThroughItsStartup() throws Exception {
		Organization claimed = team("Güneş Team", "GNST2345");
		invite("Güneş (davet adı)", "GNST2345", claimed.getId(),
				program(ProgramStatus.ACTIVE, ProgramType.STARTUP_MENTORING));

		check("GNST2345").andExpect(jsonPath("$.teamName").value("Güneş Team"));
	}

	@Test
	void refusesABlankOrOverlongCode() throws Exception {
		check(" ").andExpect(status().isBadRequest());
		check("A".repeat(33)).andExpect(status().isBadRequest());
	}

	@Test
	void isClosedToCallersWithoutTheProxyLogin() throws Exception {
		mockMvc.perform(post(CHECK)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\": \"YLDZ2345\"}"))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions check(String code) throws Exception {
		return mockMvc.perform(post(CHECK)
				.with(httpBasic("test", "test"))
				.header(ClientAddressKey.HEADER, "10.1.0." + ++visitor)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\": \"" + code + "\"}"));
	}

	private Organization team(String name, String code) {
		Organization organization = new Organization();
		organization.setName(name);
		organization.setInviteCode(code);
		return organizationRepository.saveAndFlush(organization);
	}

	private Program program(ProgramStatus status, ProgramType type) {
		Program created = new Program();
		created.setId(UUID.randomUUID());
		created.setName("Program " + created.getId());
		created.setProgramType(type);
		created.setCreatedBy(UUID.randomUUID());
		created.setCreatedAt(Instant.now());
		created.setStatus(status);
		return programRepository.saveAndFlush(created);
	}

	private void invite(String teamName, String code, UUID organizationId, Program program) {
		ProgramTeamInvite invite = new ProgramTeamInvite();
		invite.setId(UUID.randomUUID());
		invite.setProgramId(program.getId());
		invite.setTeamName(teamName);
		invite.setLeadEmail(UUID.randomUUID() + "@code-check.test");
		invite.setCode(code);
		invite.setOrganizationId(organizationId);
		invite.setCreatedBy(UUID.randomUUID());
		invite.setCreatedAt(Instant.now());
		programTeamInviteRepository.saveAndFlush(invite);
	}
}
