package com.dps.teknokent.integration;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramCoordinator;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.ProgramCoordinatorRepository;
import com.dps.teknokent.repository.ProgramRepository;

/**
 * The program guard admits a coordinator or an admin only while their account is approved: a coordinator an admin
 * deactivated keeps a browser session until the frontend re-checks it, and must not read or change a program in that
 * time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CoordinatorGuardIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AppUserRepository users;
	@Autowired
	private ProgramRepository programs;
	@Autowired
	private ProgramCoordinatorRepository programCoordinators;

	private AppUser admin;
	private AppUser coordinator;
	private Program program;

	@BeforeEach
	void setUp() {
		admin = user("Approved Admin", UserRole.ADMIN, UserAccountStatus.APPROVED);
		coordinator = user("Program Coordinator", UserRole.COORDINATOR, UserAccountStatus.APPROVED);
		program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Guard Program " + UUID.randomUUID());
		program.setCreatedBy(admin.getId());
		program.setCreatedAt(Instant.now());
		program.setStatus(ProgramStatus.ACTIVE);
		program = programs.saveAndFlush(program);
		ProgramCoordinator link = new ProgramCoordinator();
		link.setId(UUID.randomUUID());
		link.setProgramId(program.getId());
		link.setUserId(coordinator.getId());
		link.setAddedBy(admin.getId());
		link.setCreatedAt(Instant.now());
		programCoordinators.saveAndFlush(link);
	}

	@Test
	void anApprovedCoordinatorReadsAndChangesTheirProgram() throws Exception {
		read(coordinator).andExpect(status().isOk());
		rename(coordinator).andExpect(status().isOk());
	}

	@Test
	void aDeactivatedCoordinatorCanNeitherReadNorChangeTheirProgram() throws Exception {
		coordinator.setStatus(UserAccountStatus.REJECTED);
		users.saveAndFlush(coordinator);

		read(coordinator).andExpect(status().isForbidden());
		rename(coordinator).andExpect(status().isForbidden());
	}

	@Test
	void aPendingCoordinatorIsRefusedToo() throws Exception {
		coordinator.setStatus(UserAccountStatus.PENDING);
		users.saveAndFlush(coordinator);

		read(coordinator).andExpect(status().isForbidden());
	}

	@Test
	void anApprovedAdminReadsAnyProgram() throws Exception {
		read(admin).andExpect(status().isOk());
	}

	@Test
	void anAdminRoleWithoutAnApprovedAccountIsRefused() throws Exception {
		AppUser waiting = user("Waiting Admin", UserRole.ADMIN, UserAccountStatus.PENDING);

		read(waiting).andExpect(status().isForbidden());
	}

	private ResultActions read(AppUser actor) throws Exception {
		return mockMvc.perform(get("/api/coordinator/programs/{id}", program.getId())
				.param("actorId", actor.getId().toString())
				.with(httpBasic("test", "test")));
	}

	private ResultActions rename(AppUser actor) throws Exception {
		return mockMvc.perform(put("/api/coordinator/programs/{id}", program.getId())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"actorId\":\"" + actor.getId() + "\",\"name\":\"Renamed program\"}")
				.with(httpBasic("test", "test")));
	}

	private AppUser user(String name, UserRole role, UserAccountStatus accountStatus) {
		AppUser user = new AppUser();
		user.setFullName(name);
		user.setEmail(UUID.randomUUID() + "@guard.test");
		user.setRole(role);
		user.setStatus(accountStatus);
		return users.saveAndFlush(user);
	}
}
