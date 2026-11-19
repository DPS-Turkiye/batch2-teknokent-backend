package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.OcrMode;
import com.dps.teknokent.domain.WeekVisibility;
import com.dps.teknokent.dto.requests.ReadWeeklyProgramRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest;
import com.dps.teknokent.dto.responses.WeeklyProgramProposalResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.MeetingSlotTimes;

import tools.jackson.databind.json.JsonMapper;

/**
 * Reading a weekly program for a program that already has a saved plan, on the real repositories and transaction
 * manager: the saved weeks' sessions load lazily, and the read runs outside a transaction (open-in-view is off).
 */
@SpringBootTest
class WeeklyProgramServiceTransactionTest {

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-00000000c0f1");
	private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-00000000d0f1");

	@Autowired
	private WeeklyProgramService service;
	@Autowired
	private ProgramWeekRepository programWeekRepository;
	@Autowired
	private ProgramPhaseRepository programPhaseRepository;
	@MockitoBean
	private CoordinatorProgramService coordinatorProgramService;
	@MockitoBean
	private StoredDocumentRepository storedDocumentRepository;
	@MockitoBean
	private ObjectStorage objectStorage;
	@MockitoBean
	private PdfDocumentTextExtractor textExtractor;
	@MockitoBean
	private LocalPiiRedactionService piiRedactionService;
	@MockitoBean
	private AiProviderClient aiProviderClient;
	@MockitoBean
	private AiFeatures aiFeatures;
	@MockitoBean
	private StringRedisTemplate redisTemplate;

	private final UUID programId = UUID.randomUUID();
	private LocalDate today;
	private Program program;

	@BeforeEach
	void setUp() {
		today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		program = new Program();
		program.setId(programId);
		program.setStartDate(today.minusDays(21));
		program.setEndDate(today.plusDays(48));
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR_ID, programId)).thenReturn(program);
		StoredDocument document = new StoredDocument();
		document.setId(DOCUMENT_ID);
		document.setUserId(COORDINATOR_ID);
		document.setKind(DocumentKind.WEEKLY_PROGRAM);
		document.setS3Key("weekly-key");
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(document));
		when(objectStorage.require("weekly-key")).thenReturn(new byte[] { 1 });
		when(textExtractor.extract(any(), eq(OcrMode.AUTO)))
				.thenReturn(new ExtractionResult("Hafta 1", 1, List.of(), false));
		when(piiRedactionService.redact("Hafta 1")).thenReturn(new RedactionResult("Hafta 1", Map.of(), 0));
	}

	@AfterEach
	void tearDown() {
		programWeekRepository.deleteAll(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId));
		programPhaseRepository.deleteAll(programPhaseRepository.findByProgramIdOrderByPositionAsc(programId));
	}

	@Test
	void aProgramWithSavedWeeksAndSessionsCanBeReadAgainWithNoTransactionAcrossTheAiCall() {
		// Saved the way a dated plan is: the later weeks, with their own dates and a workshop session each.
		for (int number = 4; number <= 6; number++) {
			programWeekRepository.save(savedWeek(number));
		}
		ProgramPhase phase = new ProgramPhase();
		phase.setId(UUID.randomUUID());
		phase.setProgramId(programId);
		phase.setName("Geliştirme");
		phase.setPosition(0);
		phase.setStartDate(today);
		phase.setEndDate(today.plusDays(20));
		programPhaseRepository.save(phase);
		AtomicBoolean transactionOpen = new AtomicBoolean(true);
		when(aiProviderClient.weeklyProgram(anyString(), any(), any(), any())).thenAnswer(call -> {
			transactionOpen.set(TransactionSynchronizationManager.isActualTransactionActive());
			return JsonMapper.builder().build().readTree("""
					{"weeks": [{"week": 1, "source_date": null, "title": "Tanışma", "topics": [], "activities": [],
					  "deliverables": [], "phase": null}],
					 "phases": [], "notes": [], "warnings": [], "special_days": []}
					""");
		});

		WeeklyProgramProposalResponse proposal = service.read(COORDINATOR_ID, programId,
				new ReadWeeklyProgramRequest(null, DOCUMENT_ID));

		assertThat(transactionOpen).isFalse();
		assertThat(proposal.existing().weekCount()).isEqualTo(3);
		assertThat(proposal.existing().phaseCount()).isEqualTo(1);
		assertThat(proposal.existing().currentWeek()).as("week 4's printed dates contain today").isEqualTo(4);
	}

	@Test
	void savingAReadAgainKeepsEachWeeksMenteeVisibilityByNumberAndDropsARemovedWeeks() {
		for (int number = 4; number <= 6; number++) {
			programWeekRepository.save(savedWeek(number));
		}
		setVisibility(5, WeekVisibility.HIDDEN);
		setVisibility(6, WeekVisibility.SHOWN);
		List<ReplaceProgramWeeksRequest.WeekInput> file = IntStream.rangeClosed(1, 5)
				.mapToObj(number -> new ReplaceProgramWeeksRequest.WeekInput(number, "Dosya " + number, List.of(),
						List.of(), List.of(), false))
				.toList();

		service.save(COORDINATOR_ID, programId,
				new SaveWeeklyProgramRequest(null, DOCUMENT_ID, file, List.of(), 0, 3, true));

		assertThat(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(programId))
				.extracting(ProgramWeek::getWeekNumber, ProgramWeek::getMenteeVisibility)
				.containsExactly(tuple(1, WeekVisibility.AUTO),
						tuple(2, WeekVisibility.AUTO),
						tuple(3, WeekVisibility.AUTO),
						tuple(4, WeekVisibility.AUTO),
						tuple(5, WeekVisibility.HIDDEN));
	}

	private void setVisibility(int number, WeekVisibility visibility) {
		ProgramWeek week = programWeekRepository.findByProgramIdAndWeekNumber(programId, number).orElseThrow();
		week.setMenteeVisibility(visibility);
		programWeekRepository.save(week);
	}

	private ProgramWeek savedWeek(int number) {
		LocalDate start = today.plusDays(7L * (number - 4));
		ProgramWeek week = new ProgramWeek();
		week.setId(UUID.randomUUID());
		week.setProgramId(programId);
		week.setWeekNumber(number);
		week.setTitle("Hafta " + number);
		week.setStartDate(start);
		week.setEndDate(start.plusDays(6));
		ProgramWeekSession session = new ProgramWeekSession();
		session.setDate(start.plusDays(2));
		session.setStartTime(LocalTime.of(14, 0));
		session.setTitle("Workshop");
		week.setSessions(List.of(session));
		week.setUpdatedAt(Instant.now());
		return week;
	}
}
