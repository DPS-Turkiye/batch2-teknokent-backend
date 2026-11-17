package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentor;

import jakarta.persistence.EntityManager;

/**
 * Runs through the real Spring Data projection proxy: the repository package is null-marked, so a getter that is
 * not {@code @Nullable} throws when its column is NULL.
 */
@DataJpaTest
class ProgramMentorProgramNamesQueryTest {

	private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");

	@Autowired
	private ProgramMentorRepository repository;
	@Autowired
	private EntityManager entityManager;

	@Test
	void readsAnAcceptedMembershipWhoseRoleIsNull() {
		AppUser mentor = mentor("role-null-mentor@tekno.com");
		Program program = program("Teknokent Hızlandırma");
		membership(program.getId(), mentor.getId(), null);
		entityManager.flush();
		entityManager.clear();

		List<MentorProgramName> rows = repository.findProgramNamesByMentorIds(
				List.of(mentor.getId()), ProgramMentorStatus.ACCEPTED);

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.getMentorId()).isEqualTo(mentor.getId());
			assertThat(row.getProgramId()).isEqualTo(program.getId());
			assertThat(row.getProgramName()).isEqualTo("Teknokent Hızlandırma");
			assertThat(row.getProgramStatus()).isEqualTo(ProgramStatus.ACTIVE);
			assertThat(row.getRole()).isNull();
		});
	}

	@Test
	void keepsTheRoleWhenItIsSet() {
		AppUser mentor = mentor("role-set-mentor@tekno.com");
		Program program = program("Growth Circuit");
		membership(program.getId(), mentor.getId(), "Finans mentoru");
		entityManager.flush();
		entityManager.clear();

		assertThat(repository.findProgramNamesByMentorIds(List.of(mentor.getId()), ProgramMentorStatus.ACCEPTED))
				.extracting(MentorProgramName::getRole).containsExactly("Finans mentoru");
	}

	private AppUser mentor(String email) {
		AppUser user = new AppUser();
		user.setEmail(email);
		user.setFullName(email);
		user.setRole(UserRole.MENTOR);
		entityManager.persist(user);
		return user;
	}

	private Program program(String name) {
		AppUser coordinator = new AppUser();
		coordinator.setEmail(UUID.randomUUID() + "@coordinator.test");
		coordinator.setFullName("Koordinatör");
		coordinator.setRole(UserRole.COORDINATOR);
		entityManager.persist(coordinator);
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName(name);
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(NOW);
		entityManager.persist(program);
		return program;
	}

	private void membership(UUID programId, UUID mentorId, @Nullable String role) {
		ProgramMentor membership = new ProgramMentor();
		membership.setId(UUID.randomUUID());
		membership.setProgramId(programId);
		membership.setMentorId(mentorId);
		membership.setStatus(ProgramMentorStatus.ACCEPTED);
		membership.setInvitedBy(ProgramMentorInvitedBy.COORDINATOR);
		membership.setCreatedAt(NOW);
		membership.setDecidedAt(NOW);
		membership.setRole(role);
		entityManager.persist(membership);
	}
}
