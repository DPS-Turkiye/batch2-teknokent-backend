package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.WeeklyProgramWarning;
import com.dps.teknokent.dto.requests.ReadWeeklyProgramRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramPhasesRequest;
import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.requests.SaveWeeklyProgramRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;
import com.dps.teknokent.dto.responses.WeeklyProgramProposalResponse;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramPhase;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.StoredDocument;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.ProgramPhaseRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.repository.StoredDocumentRepository;
import com.dps.teknokent.service.LocalPiiRedactionService.RedactionResult;
import com.dps.teknokent.service.PdfDocumentTextExtractor.ExtractionResult;
import com.dps.teknokent.util.JsonUtils;
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.ProgramCalendar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class WeeklyProgramServiceTest {

	private static final UUID COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
	private static final UUID OTHER_COORDINATOR_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
	private static final UUID PROGRAM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
	private static final UUID DOCUMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
	// A Tuesday start, as in a real weekly program; the end leaves room for a demo day.
	private static final LocalDate START = LocalDate.of(2026, 10, 27);
	private static final LocalDate END = LocalDate.of(2027, 1, 9);

	private final JsonUtils jsonUtils = new JsonUtils(new ObjectMapper());

	@Mock
	private CoordinatorProgramService coordinatorProgramService;
	@Mock
	private ProgramWeekPlanService programWeekPlanService;
	@Mock
	private ProgramPhaseRepository programPhaseRepository;
	@Mock
	private ProgramWeekRepository programWeekRepository;
	@Mock
	private StoredDocumentRepository storedDocumentRepository;
	@Mock
	private ObjectStorage objectStorage;
	@Mock
	private PdfDocumentTextExtractor textExtractor;
	@Mock
	private LocalPiiRedactionService piiRedactionService;
	@Mock
	private AiProviderClient aiProviderClient;

	private WeeklyProgramService service;
	private Program program;

	@BeforeEach
	void setUp() {
		service = new WeeklyProgramService(coordinatorProgramService, programWeekPlanService, programPhaseRepository,
				programWeekRepository, storedDocumentRepository, objectStorage, textExtractor, piiRedactionService,
				aiProviderClient, jsonUtils);
		program = new Program();
		program.setId(PROGRAM_ID);
		program.setStartDate(START);
		program.setEndDate(END);
	}

	@Test
	void readsTheRedactedFileIntoWeeksAndPhasesOnTheProgramCalendar() {
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		stubText("Hafta 1 · İletişim: deniz@example.com", "Hafta 1 · İletişim: [EMAIL]", 1);
		when(aiProviderClient.weeklyProgram(anyString(), any(), any(), any())).thenReturn(lookAlikeAnswer());

		WeeklyProgramProposalResponse proposal = service.read(COORDINATOR_ID, PROGRAM_ID, readRequest());

		verify(aiProviderClient).weeklyProgram(eq("Hafta 1 · İletişim: [EMAIL]"), eq(START), eq(END), any());
		assertThat(proposal.totalWeeks()).isEqualTo(11);
		assertThat(proposal.pageCount()).isEqualTo(1);
		assertThat(proposal.weeks()).extracting(ProgramWeekPlanResponse.Week::week)
				.containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
		ProgramWeekPlanResponse.Week visit = proposal.weeks().get(2);
		assertThat(visit.title()).isEqualTo("Saha Gezisi 1");
		assertThat(visit.startDate()).isEqualTo(LocalDate.of(2026, 11, 10));
		assertThat(visit.endDate()).isEqualTo(LocalDate.of(2026, 11, 16));
		assertThat(visit.activities()).containsExactly("Ziyaret: Örnek Teknopark ofisleri");
		assertThat(visit.topics()).as("the table's \"–\" means nothing that week").isEmpty();
		assertThat(proposal.phases()).containsExactly(
				new WeeklyProgramProposalResponse.Phase("Problemi Keşfet", "Problem tanımı", 1, 3,
						START, LocalDate.of(2026, 11, 16)),
				new WeeklyProgramProposalResponse.Phase("Fikir ve Değer", null, 4, 5,
						LocalDate.of(2026, 11, 17), LocalDate.of(2026, 11, 30)),
				new WeeklyProgramProposalResponse.Phase("Pazar ve İş Modeli", null, 6, 8,
						LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 21)),
				new WeeklyProgramProposalResponse.Phase("Doğrula ve Sun", "Sunum ve 90 günlük plan", 9, 10,
						LocalDate.of(2026, 12, 22), LocalDate.of(2027, 1, 4)));
		assertThat(proposal.warnings()).containsExactly(
				new WeeklyProgramProposalResponse.Warning(WeeklyProgramWarning.DATES_DIFFER, List.of(8, 10)));
		assertThat(proposal.notes()).containsExactly("9 Ocak Cumartesi — Demo Day");
		assertThat(proposal.existing()).isEqualTo(new WeeklyProgramProposalResponse.Existing(0, 0,
				ProgramCalendar.currentWeek(START, END, LocalDate.now(MeetingSlotTimes.APP_ZONE))));
	}

	@Test
	void weeksPastTheProgramAreDroppedAndFlaggedAndARepeatedWeekKeepsItsFirstEntry() {
		JsonNode answer = json("""
				{"weeks": [
				  %s,
				  %s,
				  %s,
				  {"week": null, "source_date": "2026-11-04", "title": "Yalnızca tarihli", "topics": [],
				   "activities": [], "deliverables": [], "phase": "Problem"}
				], "phases": [], "notes": [], "warnings": []}
				""".formatted(week(1, null, "Tanışma", "Problem"), week(1, null, "Yine 1", "Problem"),
				week(12, null, "Program dışı", "Problem")));

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.weeks()).extracting(ProgramWeekPlanResponse.Week::title)
				.containsExactly("Tanışma", "Yalnızca tarihli");
		assertThat(proposal.weeks().get(1).inferred()).as("placed by its date, not its number").isTrue();
		assertThat(proposal.warnings()).containsExactly(
				new WeeklyProgramProposalResponse.Warning(WeeklyProgramWarning.WEEKS_DROPPED, List.of(12)),
				new WeeklyProgramProposalResponse.Warning(WeeklyProgramWarning.PHASES_REBUILT, List.of()));
		assertThat(proposal.phases()).extracting(WeeklyProgramProposalResponse.Phase::name).containsExactly("Problem");
	}

	@Test
	void weeksWithoutPhasesAreFlagged() {
		JsonNode answer = json("{\"weeks\": [" + week(1, null, "Tanışma", null)
				+ "], \"phases\": [], \"notes\": [], \"warnings\": [\"Tablo eksik\"]}");

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.phases()).isEmpty();
		assertThat(proposal.warnings()).extracting(WeeklyProgramProposalResponse.Warning::code)
				.containsExactly(WeeklyProgramWarning.NO_PHASES);
		assertThat(proposal.readingWarnings()).containsExactly("Tablo eksik");
	}

	@Test
	void aLongFileIsCutBeforeTheAiCallAndFlagged() {
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		String longText = "x".repeat(WeeklyProgramService.MAX_TEXT_LENGTH + 10);
		stubText(longText, longText, 3);
		when(aiProviderClient.weeklyProgram(anyString(), any(), any(), any())).thenReturn(lookAlikeAnswer());

		WeeklyProgramProposalResponse proposal = service.read(COORDINATOR_ID, PROGRAM_ID, readRequest());

		ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
		verify(aiProviderClient).weeklyProgram(sent.capture(), any(), any(), any());
		assertThat(sent.getValue()).hasSize(WeeklyProgramService.MAX_TEXT_LENGTH);
		assertThat(proposal.warnings()).extracting(WeeklyProgramProposalResponse.Warning::code)
				.startsWith(WeeklyProgramWarning.TEXT_CUT);
	}

	@Test
	void aFileWithNoReadableTextIsRefusedBeforeTheAiCall() {
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		stubText("  ", "  ", 1);

		assertThatThrownBy(() -> service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(InvalidRequestException.class);
		verify(aiProviderClient, never()).weeklyProgram(anyString(), any(), any(), any());
	}

	@Test
	void aProgramWithoutDatesCannotBeRead() {
		program.setEndDate(null);
		editing();

		assertThatThrownBy(() -> service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("dates");
		verify(storedDocumentRepository, never()).findById(any());
	}

	@Test
	void anotherCoordinatorsUploadOrAnotherKindOfFileReadsAsMissing() {
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(OTHER_COORDINATOR_ID)));
		assertThatThrownBy(() -> service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(ResourceNotFoundException.class);

		StoredDocument cv = weeklyProgram(COORDINATOR_ID);
		cv.setKind(DocumentKind.CV.value());
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(cv));
		assertThatThrownBy(() -> service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(aiProviderClient, never()).weeklyProgram(anyString(), any(), any(), any());
	}

	@Test
	void theProgramsOwnFileCanBeReadAgainByACoCoordinator() {
		program.setSyllabusDocumentId(DOCUMENT_ID);
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(OTHER_COORDINATOR_ID)));
		stubText("Hafta 1", "Hafta 1", 1);
		when(aiProviderClient.weeklyProgram(anyString(), any(), any(), any())).thenReturn(lookAlikeAnswer());

		assertThat(service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()).weeks()).hasSize(10);
	}

	@Test
	void savingWritesThePhasesOnTheCalendarTheWeeksAndTheFileTogether() {
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		when(programPhaseRepository.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(List.of());
		when(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of());
		List<ReplaceProgramWeeksRequest.WeekInput> weeks = List.of(weekInput(1, "Tanışma"), weekInput(4, "Fikir"));

		service.save(COORDINATOR_ID, PROGRAM_ID, new SaveWeeklyProgramRequest(null, DOCUMENT_ID, weeks, List.of(
				new SaveWeeklyProgramRequest.PhaseInput(" Problem ", "Problem tanımı", 1, 3),
				new SaveWeeklyProgramRequest.PhaseInput("Fikir", null, 4, 11)), 0, 0, false));

		verify(coordinatorProgramService).writePhases(PROGRAM_ID, List.of(
				new ReplaceProgramPhasesRequest.PhaseInput("Problem", START, LocalDate.of(2026, 11, 16),
						"Problem tanımı"),
				new ReplaceProgramPhasesRequest.PhaseInput("Fikir", LocalDate.of(2026, 11, 17), END, null)));
		verify(programWeekPlanService).writeWeeks(program, COORDINATOR_ID, weeks);
		verify(coordinatorProgramService).attachSyllabus(program, COORDINATOR_ID, DOCUMENT_ID);
	}

	@Test
	void aPlanThatChangedSinceTheReadIsNotOverwritten() {
		editing();
		when(programPhaseRepository.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(List.of(new ProgramPhase()));
		when(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of());

		assertThatThrownBy(() -> service.save(COORDINATOR_ID, PROGRAM_ID, new SaveWeeklyProgramRequest(null,
				DOCUMENT_ID, List.of(weekInput(1, "Tanışma")), List.of(), 0, 0, true)))
				.isInstanceOf(ConflictException.class);
		verify(coordinatorProgramService, never()).writePhases(any(), any());
		verify(programWeekPlanService, never()).writeWeeks(any(), any(), any());
	}

	@Test
	void overlappingOrOutOfProgramPhasesAreRefused() {
		editing();
		SaveWeeklyProgramRequest overlapping = new SaveWeeklyProgramRequest(null, DOCUMENT_ID, List.of(), List.of(
				new SaveWeeklyProgramRequest.PhaseInput("A", null, 1, 3),
				new SaveWeeklyProgramRequest.PhaseInput("B", null, 3, 4)), 0, 0, true);
		SaveWeeklyProgramRequest pastTheEnd = new SaveWeeklyProgramRequest(null, DOCUMENT_ID, List.of(), List.of(
				new SaveWeeklyProgramRequest.PhaseInput("A", null, 1, 12)), 0, 0, true);

		assertThatThrownBy(() -> service.save(COORDINATOR_ID, PROGRAM_ID, overlapping))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> service.save(COORDINATOR_ID, PROGRAM_ID, pastTheEnd))
				.isInstanceOf(InvalidRequestException.class);
		verify(coordinatorProgramService, never()).writePhases(any(), any());
	}

	@Test
	void aRunningProgramKeepsItsPastWeeksAndCompletedPhasesWhenAsked() {
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		program.setStartDate(today.minusDays(14));
		program.setEndDate(today.plusDays(60));
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		when(programPhaseRepository.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(List.of(
				phase("Eski Keşif", today.minusDays(14), today.minusDays(1))));
		when(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(
				savedWeek(1, "Eski 1"), savedWeek(2, "Eski 2"), savedWeek(3, "Eski 3")));

		service.save(COORDINATOR_ID, PROGRAM_ID, new SaveWeeklyProgramRequest(null, DOCUMENT_ID,
				List.of(weekInput(1, "Yeni 1"), weekInput(3, "Yeni 3"), weekInput(4, "Yeni 4")), List.of(
						new SaveWeeklyProgramRequest.PhaseInput("Problem", null, 1, 4),
						new SaveWeeklyProgramRequest.PhaseInput("Pazar", null, 5, 6)), 1, 3, true));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReplaceProgramPhasesRequest.PhaseInput>> phases = ArgumentCaptor.forClass(List.class);
		verify(coordinatorProgramService).writePhases(eq(PROGRAM_ID), phases.capture());
		assertThat(phases.getValue()).extracting(ReplaceProgramPhasesRequest.PhaseInput::name)
				.containsExactly("Eski Keşif", "Problem", "Pazar");
		assertThat(phases.getValue().get(1).startDate()).isEqualTo(today);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReplaceProgramWeeksRequest.WeekInput>> weeks = ArgumentCaptor.forClass(List.class);
		verify(programWeekPlanService).writeWeeks(eq(program), eq(COORDINATOR_ID), weeks.capture());
		assertThat(weeks.getValue()).extracting(ReplaceProgramWeeksRequest.WeekInput::title)
				.containsExactly("Eski 1", "Eski 2", "Yeni 3", "Yeni 4");
	}

	@Test
	void anEndedProgramKeepsEveryPastWeekIncludingTheLast() {
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		program.setStartDate(today.minusDays(70));
		program.setEndDate(today.minusDays(8));
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		when(programPhaseRepository.findByProgramIdOrderByPositionAsc(PROGRAM_ID)).thenReturn(List.of());
		when(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(
				savedWeek(1, "Eski 1"), savedWeek(9, "Eski 9")));

		service.save(COORDINATOR_ID, PROGRAM_ID, new SaveWeeklyProgramRequest(null, DOCUMENT_ID,
				List.of(weekInput(9, "Yeni 9")), List.of(), 0, 2, true));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReplaceProgramWeeksRequest.WeekInput>> weeks = ArgumentCaptor.forClass(List.class);
		verify(programWeekPlanService).writeWeeks(eq(program), eq(COORDINATOR_ID), weeks.capture());
		assertThat(weeks.getValue()).extracting(ReplaceProgramWeeksRequest.WeekInput::title)
				.containsExactly("Eski 1", "Eski 9");
	}

	@Test
	void completedPhasesStayAndAPhaseRunningIntoTodayStartsAfterThem() {
		LocalDate today = LocalDate.of(2026, 11, 20);
		List<ReplaceProgramPhasesRequest.PhaseInput> kept = WeeklyProgramService.keepCompletedPhases(
				List.of(phase("Keşif", START, LocalDate.of(2026, 11, 9)),
						phase("Şimdiki", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30))),
				List.of(
						new ReplaceProgramPhasesRequest.PhaseInput("Yeni geçmiş", START, LocalDate.of(2026, 11, 2), null),
						new ReplaceProgramPhasesRequest.PhaseInput("Yeni şimdiki", LocalDate.of(2026, 11, 3),
								LocalDate.of(2026, 11, 23), null),
						new ReplaceProgramPhasesRequest.PhaseInput("Yeni sonraki", LocalDate.of(2026, 11, 24),
								LocalDate.of(2026, 12, 7), null)),
				today);

		assertThat(kept).containsExactly(
				new ReplaceProgramPhasesRequest.PhaseInput("Keşif", START, LocalDate.of(2026, 11, 9), null),
				new ReplaceProgramPhasesRequest.PhaseInput("Yeni şimdiki", LocalDate.of(2026, 11, 10),
						LocalDate.of(2026, 11, 23), null),
				new ReplaceProgramPhasesRequest.PhaseInput("Yeni sonraki", LocalDate.of(2026, 11, 24),
						LocalDate.of(2026, 12, 7), null));
	}

	private void editing() {
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR_ID, PROGRAM_ID)).thenReturn(program);
	}

	private void stubText(String raw, String redacted, int pages) {
		when(objectStorage.get("weekly-key")).thenReturn(new byte[] { 1 });
		when(textExtractor.extract(any(), eq("auto"))).thenReturn(new ExtractionResult(raw, pages, List.of(), false));
		when(piiRedactionService.redact(raw)).thenReturn(new RedactionResult(redacted, Map.of(), 0));
	}

	private static ReadWeeklyProgramRequest readRequest() {
		return new ReadWeeklyProgramRequest(null, DOCUMENT_ID);
	}

	private static StoredDocument weeklyProgram(UUID ownerId) {
		StoredDocument document = new StoredDocument();
		document.setId(DOCUMENT_ID);
		document.setUserId(ownerId);
		document.setKind(DocumentKind.WEEKLY_PROGRAM.value());
		document.setS3Key("weekly-key");
		return document;
	}

	private static ProgramPhase phase(String name, LocalDate start, LocalDate end) {
		ProgramPhase phase = new ProgramPhase();
		phase.setName(name);
		phase.setStartDate(start);
		phase.setEndDate(end);
		return phase;
	}

	private static ProgramWeek savedWeek(int number, String title) {
		ProgramWeek week = new ProgramWeek();
		week.setWeekNumber(number);
		week.setTitle(title);
		return week;
	}

	private static ReplaceProgramWeeksRequest.WeekInput weekInput(int number, String title) {
		return new ReplaceProgramWeeksRequest.WeekInput(number, title, List.of(), List.of(), List.of(), false);
	}

	/**
	 * The AI's answer for a synthetic look-alike of a real weekly program: ten Tuesday weeks with two visit weeks,
	 * two weeks printed on a Monday (off the calendar), a demo day after the last week, invented names throughout.
	 */
	private JsonNode lookAlikeAnswer() {
		List<String> weeks = new ArrayList<>();
		weeks.add(week(1, "2026-10-27", "Yola Çık", "Problemi Keşfet"));
		weeks.add(week(2, "2026-11-03", "Problemi Anla", "Problemi Keşfet"));
		weeks.add("""
				{"week": 3, "source_date": "2026-11-10", "title": "Saha Gezisi 1", "topics": ["–"],
				 "activities": ["Ziyaret: Örnek Teknopark ofisleri"], "deliverables": ["3 gözlem yaz"],
				 "phase": "Problemi Keşfet"}""");
		weeks.add(week(4, "2026-11-17", "Fikir Üret", "Fikir ve Değer"));
		weeks.add(week(5, "2026-11-24", "Değer Önerisi", "Fikir ve Değer"));
		weeks.add(week(6, "2026-12-01", "Pazarı Tanı", "Pazar ve İş Modeli"));
		weeks.add(week(7, "2026-12-08", "İş Modeli", "Pazar ve İş Modeli"));
		weeks.add(week(8, "2026-12-14", "Saha Gezisi 2", "Pazar ve İş Modeli"));
		weeks.add(week(9, "2026-12-22", "Doğrula", "Doğrula ve Sun"));
		weeks.add(week(10, "2026-12-28", "Sun", "Doğrula ve Sun"));
		return json("""
				{"weeks": [%s],
				 "phases": [
				   {"name": "Problemi Keşfet", "expected_output": "Problem tanımı", "first_week": 1, "last_week": 3},
				   {"name": "Fikir ve Değer", "expected_output": null, "first_week": 4, "last_week": 5},
				   {"name": "Pazar ve İş Modeli", "expected_output": "  ", "first_week": 6, "last_week": 8},
				   {"name": "Doğrula ve Sun", "expected_output": "Sunum ve 90 günlük plan", "first_week": 9,
				    "last_week": 10}],
				 "notes": ["9 Ocak Cumartesi — Demo Day", "–"],
				 "warnings": []}
				""".formatted(String.join(",", weeks)));
	}

	private static String week(int number, String date, String title, String phase) {
		return """
				{"week": %d, "source_date": %s, "title": "%s", "topics": ["Oturum: %s", "Konuşmacı: Deniz Örnek"],
				 "activities": ["Workshop: Ekip çalışması"], "deliverables": ["Haftalık görev"], "phase": %s}"""
				.formatted(number, date == null ? "null" : "\"" + date + "\"", title, title,
						phase == null ? "null" : "\"" + phase + "\"");
	}

	private JsonNode json(String value) {
		return jsonUtils.readTree(value);
	}
}
