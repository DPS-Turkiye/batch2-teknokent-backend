package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest.PhaseInput;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.ResourceNotFoundException;
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
				null, List.of(new PhaseInput("Başvuru", start, start.plusDays(27), null),
						new PhaseInput("Seçim", null, null, null)), null));
		entityManager.flush();

		var detail = coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(
						new PhaseInput("Seçim", start, start.plusDays(10), null),
						new PhaseInput("Eşleşme", null, null, null),
						new PhaseInput("Demo Day", null, null, null)), null));
		entityManager.flush();
		entityManager.clear();

		assertThat(programPhaseRepository.findByProgramIdOrderByPositionAsc(program.getId()))
				.extracting(phase -> phase.getPosition() + ":" + phase.getName())
				.containsExactly("0:Seçim", "1:Eşleşme", "2:Demo Day");
		assertThat(detail.program().phases()).extracting(phase -> phase.name())
				.containsExactly("Seçim", "Eşleşme", "Demo Day");
	}

	@Test
	void anEmptyListClearsThePhases() {
		coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(new PhaseInput("Başvuru", null, null, null)), null));
		coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(), null));
		entityManager.flush();

		List<ProgramPhase> phases = programPhaseRepository.findByProgramIdOrderByPositionAsc(program.getId());
		assertThat(phases).isEmpty();
	}

	@Test
	void expectedOutputsAndTheSyllabusFileShowOnTheProgram() {
		StoredDocument syllabus = syllabusOwnedBy(coordinator);

		var detail = coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(
						new PhaseInput("Keşif", null, null, "  Problem doğrulama raporu "),
						new PhaseInput("Demo Day", null, null, " ")), syllabus.getId()));

		assertThat(detail.program().phases())
				.extracting(phase -> phase.name() + ":" + phase.expectedOutput())
				.containsExactly("Keşif:Problem doğrulama raporu", "Demo Day:null");
		assertThat(detail.syllabus().id()).isEqualTo(syllabus.getId());
		assertThat(detail.syllabus().filename()).isEqualTo("METU-Stars-2026-mufredat.pdf");
		assertThat(detail.syllabus().sizeBytes()).isEqualTo(1_200_000L);
	}

	@Test
	void someoneElsesFileCannotBecomeTheSyllabus() {
		AppUser stranger = new AppUser();
		stranger.setEmail("stranger@tekno.test");
		stranger.setFullName("Stranger");
		stranger.setRole(UserRole.COORDINATOR);
		entityManager.persist(stranger);
		StoredDocument theirs = syllabusOwnedBy(stranger);

		assertThatThrownBy(() -> coordinatorProgramService.replacePhases(coordinator.getId(), program.getId(),
				new ReplaceProgramPhasesRequest(null, List.of(), theirs.getId())))
				.isInstanceOf(ResourceNotFoundException.class);
		entityManager.clear();
		assertThat(entityManager.find(Program.class, program.getId()).getSyllabusDocumentId()).isNull();
	}

	private StoredDocument syllabusOwnedBy(AppUser owner) {
		StoredDocument document = new StoredDocument();
		document.setId(UUID.randomUUID());
		document.setUserId(owner.getId());
		document.setKind(DocumentKind.SYLLABUS.value());
		document.setOriginalFilename("METU-Stars-2026-mufredat.pdf");
		document.setContentType("application/pdf");
		document.setSizeBytes(1_200_000L);
		document.setS3Key("syllabus/" + UUID.randomUUID());
		document.setCreatedAt(Instant.now());
		entityManager.persist(document);
		entityManager.flush();
		return document;
	}
}
