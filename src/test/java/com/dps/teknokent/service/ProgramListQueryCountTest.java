package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.entity.ProgramStartup;

import jakarta.persistence.EntityManager;

/**
 * Every program screen loads this list; it read each startup's organization and each mentor one query at a time.
 * The query count must not grow with programs, startups, mentors or members.
 */
@SpringBootTest
@Transactional
@TestPropertySource(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class ProgramListQueryCountTest {

	@Autowired
	private ProgramService programService;

	@Autowired
	private EntityManager entityManager;

	private int sequence;

	@Test
	void listUsesTheSameNumberOfQueriesForOneSmallProgramAndManyLargeOnes() {
		AppUser small = persistUser(UserRole.COORDINATOR, null);
		persistProgram(small, 1, 1);
		AppUser large = persistUser(UserRole.COORDINATOR, null);
		for (int i = 0; i < 4; i++) {
			persistProgram(large, 5, 5);
		}

		long smallQueries = countQueries(() -> programService.list(small.getId()));
		long largeQueries = countQueries(() -> programService.list(large.getId()));

		assertThat(largeQueries).isEqualTo(smallQueries);
	}

	@Test
	void detailUsesTheSameNumberOfQueriesForOneStartupAndManyStartupsWithMembers() {
		AppUser coordinator = persistUser(UserRole.COORDINATOR, null);
		Program small = persistProgram(coordinator, 1, 1);
		Program large = persistProgram(coordinator, 6, 6);

		long smallQueries = countQueries(() -> programService.findDetail(coordinator.getId(), small.getId()));
		long largeQueries = countQueries(() -> programService.findDetail(coordinator.getId(), large.getId()));

		assertThat(largeQueries).isEqualTo(smallQueries);
	}

	private long countQueries(Supplier<?> call) {
		entityManager.flush();
		entityManager.clear();
		Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		statistics.clear();
		call.get();
		return statistics.getPrepareStatementCount();
	}

	private Program persistProgram(AppUser coordinator, int startups, int mentors) {
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Program " + sequence++);
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.parse("2026-10-01T10:00:00Z"));
		entityManager.persist(program);
		for (int i = 0; i < startups; i++) {
			Organization organization = new Organization();
			organization.setName("Startup " + sequence++);
			entityManager.persist(organization);
			persistUser(UserRole.MENTEE, organization);
			persistUser(UserRole.MENTEE, organization);
			ProgramStartup row = new ProgramStartup();
			row.setId(UUID.randomUUID());
			row.setProgramId(program.getId());
			row.setOrganizationId(organization.getId());
			row.setCreatedAt(Instant.parse("2026-10-01T10:00:00Z"));
			entityManager.persist(row);
		}
		for (int i = 0; i < mentors; i++) {
			ProgramMentor membership = new ProgramMentor();
			membership.setId(UUID.randomUUID());
			membership.setProgramId(program.getId());
			membership.setMentorId(persistUser(UserRole.MENTOR, null).getId());
			membership.setStatus(ProgramMentorStatus.ACCEPTED);
			membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
			membership.setCreatedAt(Instant.parse("2026-10-01T10:00:00Z"));
			entityManager.persist(membership);
		}
		return program;
	}

	private AppUser persistUser(UserRole role, Organization organization) {
		AppUser user = new AppUser();
		user.setEmail("program-count-" + sequence++ + "@tekno.com");
		user.setFullName("User " + sequence);
		user.setRole(role);
		user.setOrganization(organization);
		entityManager.persist(user);
		return user;
	}
}
