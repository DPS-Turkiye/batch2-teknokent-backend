package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest.PhaseInput;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.repository.ProgramPhaseRepository;

import jakarta.persistence.EntityManager;

/**
 * Replaces phases against a real persistence context: re-saving the same positions only works
 * because the service flushes the deletes before inserting.
 */
@DataJpaTest
@Import({ CoordinatorProgramService.class, DomainLookupService.class })
class ProgramPhasePersistenceTest {

	@Autowired
	private CoordinatorProgramService coordinatorProgramService;

	@Autowired
	private ProgramPhaseRepository programPhaseRepository;

	@Autowired
	private EntityManager entityManager;

	private AppUser coordinator;
	private Program program;

	@BeforeEach
	void setUp() {
		coordinator = new AppUser();
		coordinator.setEmail("phase-coordinator@tekno.test");
		coordinator.setFullName("Phase Coordinator");
		coordinator.setRole(UserRole.COORDINATOR);
		entityManager.persist(coordinator);

		program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("METU Stars 2026");
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.now());
		entityManager.persist(program);
		entityManager.flush();
	}

	@Test
	void replacingPhasesTwiceKeepsOnlyTheLatestInOrder() {
		LocalDate start = LocalDate.of(2026, 2, 1);
		coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(), new ReplaceProgramPhasesRequest(
				null, List.of(new PhaseInput("Başvuru", start, start.plusDays(27)), new PhaseInput("Seçim", null, null))));
		entityManager.flush();

		var detail = coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(
						new PhaseInput("Seçim", start, start.plusDays(10)),
						new PhaseInput("Eşleşme", null, null),
						new PhaseInput("Demo Day", null, null))));
		entityManager.flush();
		entityManager.clear();

		assertThat(programPhaseRepository.findByProgramIdOrderByPositionAsc(program.getId()))
				.extracting(phase -> phase.getPosition() + ":" + phase.getName())
				.containsExactly("0:Seçim", "1:Eşleşme", "2:Demo Day");
		assertThat(detail.phases()).hasSize(3);
		assertThat(detail.program().phaseCount()).isEqualTo(3);
	}

	@Test
	void anEmptyListClearsThePhases() {
		coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(new PhaseInput("Başvuru", null, null))));
		coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of()));
		entityManager.flush();

		List<ProgramPhase> phases = programPhaseRepository.findByProgramIdOrderByPositionAsc(program.getId());
		assertThat(phases).isEmpty();
	}
}
