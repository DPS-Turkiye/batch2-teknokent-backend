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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest.WeekInput;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest.PhaseInput;
import com.dps.teknokent.dto.responses.WeeklyProgramSavedResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.util.JsonUtils;

import jakarta.persistence.EntityManager;

/** The one-step save against a real persistence context: phases, weeks and the file land together. */
@DataJpaTest
@Import({ WeeklyProgramService.class, CoordinatorProgramService.class, ProgramWeekPlanService.class,
		DomainLookupService.class, LocalPiiRedactionService.class, AiFeatures.class })
class WeeklyProgramPersistenceTest {

	// Starts in the future, so the save never meets "keep the past".
	private static final LocalDate START = LocalDate.of(2030, 3, 5);

	@Autowired
	private WeeklyProgramService weeklyProgramService;
	@Autowired
	private ProgramPhaseRepository programPhaseRepository;
	@Autowired
	private ProgramWeekRepository programWeekRepository;
	@Autowired
	private EntityManager entityManager;

	@MockitoBean
	private ObjectStorage objectStorage;
	@MockitoBean
	private PdfDocumentTextExtractor textExtractor;
	@MockitoBean
	private AiProviderClient aiProviderClient;
	@MockitoBean
	private JsonUtils jsonUtils;

	private AppUser coordinator;
	private Program program;
	private StoredDocument file;

	@BeforeEach
	void setUp() {
		coordinator = new AppUser();
		coordinator.setEmail("weekly-coordinator@tekno.test");
		coordinator.setFullName("Weekly Coordinator");
		coordinator.setRole(UserRole.COORDINATOR);
		entityManager.persist(coordinator);

		program = new Program();
		program.setId(UUID.randomUUID());
		program.setName("Keşif Programı 2030");
		program.setCreatedBy(coordinator.getId());
		program.setCreatedAt(Instant.now());
		program.setStartDate(START);
		program.setEndDate(START.plusWeeks(10).minusDays(1));
		entityManager.persist(program);

		file = new StoredDocument();
		file.setId(UUID.randomUUID());
		file.setUserId(coordinator.getId());
		file.setKind(DocumentKind.WEEKLY_PROGRAM.value());
		file.setOriginalFilename("kesif-haftalik-program.pdf");
		file.setContentType("application/pdf");
		file.setSizeBytes(70_000L);
		file.setS3Key("weekly/" + UUID.randomUUID());
		file.setCreatedAt(Instant.now());
		entityManager.persist(file);
		entityManager.flush();
	}

	@Test
	void savingTwiceReplacesThePlanAndShowsTheFileOnTheProgram() {
		save(List.of(week(1, "Eski"), week(2, "Eski 2")), List.of(new PhaseInput("Eski faz", null, 1, 2)), 0, 0);
		entityManager.flush();

		WeeklyProgramSavedResponse saved = save(
				List.of(week(1, "Yola Çık"), week(2, "Problemi Anla"), week(3, "Saha Gezisi")),
				List.of(new PhaseInput("Problemi Keşfet", "Problem tanımı", 1, 3),
						new PhaseInput("Fikir ve Değer", null, 4, 10)),
				1, 2);
		entityManager.flush();
		entityManager.clear();

		assertThat(programPhaseRepository.findByProgramIdOrderByPositionAsc(program.getId()))
				.extracting(phase -> phase.getName() + ":" + phase.getStartDate() + ":" + phase.getEndDate())
				.containsExactly("Problemi Keşfet:2030-03-05:2030-03-25", "Fikir ve Değer:2030-03-26:2030-05-13");
		assertThat(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(program.getId()))
				.extracting(week -> week.getWeekNumber() + ":" + week.getTitle())
				.containsExactly("1:Yola Çık", "2:Problemi Anla", "3:Saha Gezisi");
		assertThat(saved.plan().weeks()).hasSize(3);
		assertThat(saved.program().syllabus().id()).isEqualTo(file.getId());
		assertThat(saved.program().syllabus().kind()).isEqualTo(DocumentKind.WEEKLY_PROGRAM.value());
	}

	private WeeklyProgramSavedResponse save(List<WeekInput> weeks, List<PhaseInput> phases, int seenPhases,
			int seenWeeks) {
		return weeklyProgramService.save(coordinator.getId(), program.getId(),
				new SaveWeeklyProgramRequest(null, file.getId(), weeks, phases, seenPhases, seenWeeks, true));
	}

	private static WeekInput week(int number, String title) {
		return new WeekInput(number, title, List.of("Oturum"), List.of(), List.of("Görev"), false);
	}
}
