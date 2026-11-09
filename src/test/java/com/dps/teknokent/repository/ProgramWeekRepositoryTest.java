package com.dps.teknokent.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramWeek;

import jakarta.persistence.EntityManager;

@DataJpaTest
class ProgramWeekRepositoryTest {

	@Autowired
	private ProgramWeekRepository repository;
	@Autowired
	private EntityManager entityManager;

	@Test
	void readsAProgramsWeeksInOrderAndRemovesThemTogether() {
		AppUser coordinator = new AppUser();
		coordinator.setEmail("week-coordinator@tekno.com");
		coordinator.setFullName("Koordinatör");
		coordinator.setRole(UserRole.COORDINATOR);
		entityManager.persist(coordinator);
		Program program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("İŞKUR Gençlik Programı");
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.now());
		entityManager.persist(program);
		repository.saveAndFlush(week(program.getId(), 3, "Prototip"));
		repository.saveAndFlush(week(program.getId(), 1, "Tanışma"));
		entityManager.clear();

		assertThat(repository.findByProgramIdOrderByWeekNumberAsc(program.getId()))
				.extracting(ProgramWeek::getTitle).containsExactly("Tanışma", "Prototip");
		assertThat(repository.findByProgramIdAndWeekNumber(program.getId(), 3)).isPresent();

		repository.deleteByProgramId(program.getId());
		repository.flush();
		assertThat(repository.existsByProgramId(program.getId())).isFalse();
	}

	private static ProgramWeek week(UUID programId, int number, String title) {
		ProgramWeek week = new ProgramWeek();
		week.setId(UUID.randomUUID());
		week.setProgramId(programId);
		week.setWeekNumber(number);
		week.setTitle(title);
		week.setTopics("Konu");
		week.setUpdatedAt(Instant.now());
		return week;
	}
}
