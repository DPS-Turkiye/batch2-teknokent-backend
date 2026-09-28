package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Verifies the PostgreSQL partial index that enforces one pending proposal per mentorship. */
@DataJpaTest(properties = {
		"spring.datasource.url=jdbc:postgresql://${DATABASE_HOST:localhost}:${DATABASE_PORT:5432}/${DATABASE_NAME:db}",
		"spring.datasource.driver-class-name=org.postgresql.Driver",
		"spring.datasource.username=${DATABASE_USERNAME:teknokent}",
		"spring.datasource.password=${DATABASE_PASSWORD:change-me}",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_ENABLED", matches = "true")
class MeetingProposalPendingInvariantTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID mentorshipId;
	private UUID coordinatorId;
	private UUID programId;

	@BeforeEach
	void setUp() {
		UUID mentorId = insertUser("MENTOR");
		coordinatorId = insertUser("COORDINATOR");
		UUID organizationId = UUID.randomUUID();
		mentorshipId = UUID.randomUUID();
		programId = UUID.randomUUID();

		jdbcTemplate.update("INSERT INTO organization (id, name) VALUES (?, ?)", organizationId, "Proposal invariant test");
		jdbcTemplate.update(
				"INSERT INTO mentorship (id, mentor_id, organization_id, status, start_date) VALUES (?, ?, ?, ?, ?)",
				mentorshipId, mentorId, organizationId, "ACTIVE", LocalDate.now());
		jdbcTemplate.update(
				"INSERT INTO program (id, name, created_by) VALUES (?, ?, ?)",
				programId, "Proposal invariant test", coordinatorId);
	}

	@Test
	void rejectsASecondPendingProposalForTheSameMentorship() {
		insertProposal(UUID.randomUUID(), "PENDING");

		assertThatThrownBy(() -> insertProposal(UUID.randomUUID(), "PENDING"))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void allowsSettledProposalsAlongsideOnePendingProposal() {
		insertProposal(UUID.randomUUID(), "DECLINED");
		insertProposal(UUID.randomUUID(), "WITHDRAWN");

		insertProposal(UUID.randomUUID(), "PENDING");
	}

	private UUID insertUser(String role) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO app_user (id, email, full_name, role) VALUES (?, ?, ?, ?)",
				id, id + "@proposal-test.invalid", "Proposal invariant test", role);
		return id;
	}

	private void insertProposal(UUID id, String status) {
		jdbcTemplate.update(
				"INSERT INTO meeting_proposal (id, mentorship_id, program_id, proposed_by, scheduled_at, duration_minutes, status) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?)",
				id, mentorshipId, programId, coordinatorId, Instant.parse("2026-10-01T10:00:00Z"), 30, status);
	}
}
