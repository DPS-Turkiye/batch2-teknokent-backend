package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.AiFeature;
import com.dps.teknokent.domain.DocumentKind;
import com.dps.teknokent.domain.OcrMode;
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
import com.dps.teknokent.exception.AiFeatureDisabledException;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
				aiProviderClient, TestAiFeatures.allOn());
		program = new Program();
		program.setId(PROGRAM_ID);
		program.setStartDate(START);
		program.setEndDate(END);
	}

	@Test
	void previewAndKeepPastUseStoredDatesWhenTheConfiguredProgramStartsLater() {
		LocalDate today = LocalDate.of(2026, 11, 16);
		program.setStartDate(today.plusDays(14));
		program.setEndDate(today.plusDays(42));
		ProgramWeek past = savedWeek(1, "Keep past");
		past.setStartDate(today.minusDays(14));
		past.setEndDate(today.minusDays(8));
		ProgramWeek current = savedWeek(2, "Replace current");
		current.setStartDate(today.minusDays(1));
		current.setEndDate(today.plusDays(5));
		List<ProgramWeek> saved = List.of(past, current);
		assertThat(WeeklyProgramService.firstReplacedWeek(program, saved, today)).isEqualTo(2);
		assertThat(WeeklyProgramService.keepPastWeeks(program, saved,
				List.of(weekInput(1, "New past"), weekInput(2, "New current")), today))
				.extracting(ReplaceProgramWeeksRequest.WeekInput::title).containsExactly("Keep past", "New current");
	}

	@Test
	void futureStoredWeeksAreReplaceableEvenWhenConfiguredProgramHasEnded() {
		LocalDate today = LocalDate.of(2026, 11, 16);
		program.setStartDate(today.minusDays(28));
		program.setEndDate(today.minusDays(1));
		ProgramWeek future = savedWeek(1, "Old future");
		future.setStartDate(today.plusDays(7));
		future.setEndDate(today.plusDays(13));
		var replacement = new ReplaceProgramWeeksRequest.WeekInput(1, "New future", List.of(), List.of(), List.of(),
				false,
				future.getStartDate(), future.getEndDate(), List.of());
		assertThat(WeeklyProgramService.firstReplacedWeek(program, List.of(future), today)).isNull();
		assertThat(WeeklyProgramService.keepPastWeeks(program, List.of(future), List.of(replacement), today))
				.extracting(ReplaceProgramWeeksRequest.WeekInput::title).containsExactly("New future");
	}

	@Test
	void aDatedMentionOfAnotherProgramsDemoDayStaysASessionOfItsWeek() {
		JsonNode raw = JsonMapper.builder().build().readTree(
				"""
						{"weeks":[{"week":1,"source_date":"2026-12-14","title":"Visit","topics":[],"activities":[],"deliverables":[],
						"sessions":[{"date":"2026-12-15","title":"Growth Circuit Demo Day","place":"KKM"},
						{"date":"2026-12-16","title":"Demo Day / Retro Simülasyonu","place":"ODTÜ 70"}]}],
						"special_days":[],"phases":[],"notes":[],"warnings":[]}
						""");
		var proposal = WeeklyProgramService.proposalOf(raw, LocalDate.of(2026, 12, 14), LocalDate.of(2027, 1, 9), 4,
				List.of(), 1);
		assertThat(proposal.specialDays()).isEmpty();
		assertThat(proposal.weeks().getFirst().sessions()).extracting(ProgramWeekPlanResponse.Session::title)
				.containsExactly("Growth Circuit Demo Day", "Demo Day / Retro Simülasyonu");
	}

	@Test
	void anUndatedWorkshopBecomesASessionTwoDaysAfterItsWeeksPrintedStartAndADatedOneKeepsItsDate() {
		JsonNode raw = JsonMapper.builder().build().readTree(
				"""
						{"weeks":[
						{"week":1,"source_date":"2026-10-27","title":"Keşfetmeye Başla","topics":[],"deliverables":[],
						 "activities":["Workshop: Helin & Çağrı"],
						 "sessions":[{"date":"2026-10-27","title":"Girişimcilik Mindset"}]},
						{"week":3,"source_date":"2026-11-10","title":"Ziyaret","topics":[],"deliverables":[],
						 "activities":["Workshop: Naim Zaheir – 12 Kasım Perşembe"],
						 "sessions":[{"date":"2026-11-12","title":"Naim Zaheir"}]},
						{"week":4,"source_date":null,"title":"Tarihsiz","topics":[],"deliverables":[],
						 "activities":["Workshop: Sarper"],"sessions":[]},
						{"week":null,"source_date":"2026-11-30","title":"Kanıt ve Sahne","topics":[],"deliverables":[],
						 "activities":["Workshop: Demet"],"sessions":[]},
						{"week":10,"source_date":"2026-12-28","title":"Geleceğe Bak","topics":[],"deliverables":[],
						 "activities":["Workshop: –"],"sessions":[{"date":"2027-01-04","title":"Deniz Erbil"}]}],
						"special_days":[],"phases":[],"notes":[],"warnings":[]}
						""");

		var proposal = WeeklyProgramService.proposalOf(raw, START, END, 11, List.of(), 1);

		Map<Integer, List<LocalDate>> sessionDays = new TreeMap<>();
		proposal.weeks().forEach(week -> sessionDays.put(week.week(),
				week.sessions().stream().map(ProgramWeekPlanResponse.Session::date).toList()));
		assertThat(sessionDays).containsExactly(
				Map.entry(1, List.of(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 29))),
				Map.entry(3, List.of(LocalDate.of(2026, 11, 12))),
				Map.entry(4, List.of()),
				// A row placed only by its own date is an event, not the week's printed start.
				Map.entry(5, List.of()),
				Map.entry(10, List.of(LocalDate.of(2027, 1, 4))));
		assertThat(proposal.weeks().getFirst().sessions().getLast().title()).isEqualTo("Workshop: Helin & Çağrı");
		assertThat(proposal.weeks().getFirst().activities()).containsExactly("Workshop: Helin & Çağrı");
	}

	@Test
	void aWeekReadAsTwoNumberedRowsGetsOneWorkshopFromItsFirstPrintedStart() {
		JsonNode raw = JsonMapper.builder().build().readTree(
				"""
						{"weeks":[
						{"week":2,"source_date":"2026-11-03","title":"Problem","topics":[],"deliverables":[],
						 "activities":["Workshop: Helin & Çağrı"],"sessions":[]},
						{"week":2,"source_date":"2026-11-04","title":"Problem","topics":[],"deliverables":[],
						 "activities":[],"sessions":[]}],
						"special_days":[],"phases":[],"notes":[],"warnings":[]}
						""");

		var proposal = WeeklyProgramService.proposalOf(raw, START, END, 11, List.of(), 1);

		assertThat(proposal.weeks()).singleElement()
				.satisfies(week -> assertThat(week.sessions())
						.extracting(ProgramWeekPlanResponse.Session::date, ProgramWeekPlanResponse.Session::title)
						.containsExactly(tuple(LocalDate.of(2026, 11, 5), "Workshop: Helin & Çağrı")));
	}

	@Test
	void aLaterRowsEventDateIsNotTakenAsTheWeeksPrintedStart() {
		JsonNode raw = JsonMapper.builder().build().readTree(
				"""
						{"weeks":[
						{"week":2,"source_date":null,"title":"Problem","topics":[],"deliverables":[],
						 "activities":["Workshop: Helin & Çağrı"],"sessions":[]},
						{"week":2,"source_date":"2026-11-05","title":"Problem","topics":[],"deliverables":[],
						 "activities":[],"sessions":[]}],
						"special_days":[],"phases":[],"notes":[],"warnings":[]}
						""");

		var proposal = WeeklyProgramService.proposalOf(raw, START, END, 11, List.of(), 1);

		assertThat(proposal.weeks()).singleElement().satisfies(week -> assertThat(week.sessions()).isEmpty());
	}

	@Test
	void aPhaseRunsFromThePrintedStartOfItsFirstWeekToThePrintedEndOfItsLast() {
		JsonNode result = JsonMapper.builder().build().readTree(
				"""
						{"weeks":[{"week":1,"source_date":"2026-10-27","source_end_date":null,"title":"Bir",
						"topics":[],"activities":[],"deliverables":[],"phase":"Keşif","sessions":[]},
						{"week":2,"source_date":"2026-11-03","source_end_date":"2026-11-05","title":"İki",
						"topics":[],"activities":[],"deliverables":[],"phase":"Keşif","sessions":[]}],
						"phases":[{"name":"Keşif","expected_output":null,"first_week":1,"last_week":2}],
						"notes":[],"warnings":[],"special_days":[]}
						""");
		// The program calendar starts a day earlier than the file.
		var proposal = WeeklyProgramService.proposalOf(result, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 8),
				2, List.of(), 1);

		assertThat(proposal.phases()).singleElement().satisfies(phase -> {
			assertThat(phase.startDate()).isEqualTo(LocalDate.of(2026, 10, 27));
			assertThat(phase.endDate()).isEqualTo(LocalDate.of(2026, 11, 5));
		});
	}

	@Test
	void theReadReportsTheFirstReplacedWeekByTheSavedPlansPrintedDates() {
		editing();
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		// The program calendar has not started, but the saved plan prints week 1 as running.
		program.setStartDate(today.plusDays(14));
		program.setEndDate(today.plusDays(84));
		ProgramWeek running = savedWeek(1, "Running");
		running.setStartDate(today.minusDays(1));
		running.setEndDate(today.plusDays(5));
		when(programWeekRepository.findByProgramIdOrderByWeekNumberAsc(PROGRAM_ID)).thenReturn(List.of(running));
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		stubText("raw", "redacted", 1);
		when(aiProviderClient.weeklyProgram(anyString(), any(), any(), any())).thenReturn(JsonMapper.builder().build()
				.readTree("""
						{"weeks":[{"week":1,"source_date":null,"source_end_date":null,"title":"Bir","topics":[],
						"activities":[],"deliverables":[],"phase":null,"sessions":[]}],
						"phases":[],"notes":[],"warnings":[],"special_days":[]}
						"""));

		var proposal = service.read(COORDINATOR_ID, PROGRAM_ID, readRequest());

		assertThat(proposal.existing().currentWeek()).isEqualTo(1);
	}

	@Test
	void keepsPrintedDatesSessionsAndStandaloneEventsWhenProgramDatesDiffer() {
		JsonNode result = JsonMapper.builder().build().readTree(
				"""
						{"weeks":[{"week":2,"source_date":"2026-11-09","source_end_date":"2026-11-13",
						"title":"Circuit","topics":[],"activities":[],"deliverables":[],"phase":null,
						"sessions":[{"date":"2026-11-11","start_time":"10:00","end_time":"12:00","place":"Salon","title":"Atölye"}]}],
						"phases":[],"notes":[],"warnings":[],"special_days":[{"date":"2026-11-08","start_time":"09:00",
						"end_time":null,"place":"Kampüs","title":"Bootcamp","details":["Açılış"]}]}
						""");
		var proposal = WeeklyProgramService.proposalOf(result, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1),
				1, List.of(), 1);
		assertThat(proposal.weeks()).singleElement().satisfies(week -> {
			assertThat(week.week()).isEqualTo(2);
			assertThat(week.startDate()).isEqualTo(LocalDate.of(2026, 11, 9));
			assertThat(week.endDate()).isEqualTo(LocalDate.of(2026, 11, 13));
			assertThat(week.datesFromSource()).isTrue();
			assertThat(week.sessions()).singleElement().satisfies(session -> {
				assertThat(session.date()).isEqualTo(LocalDate.of(2026, 11, 11));
				assertThat(session.place()).isEqualTo("Salon");
			});
		});
		assertThat(proposal.specialDays()).singleElement().satisfies(day -> {
			assertThat(day.title()).isEqualTo("Bootcamp");
			assertThat(day.date()).isEqualTo(LocalDate.of(2026, 11, 8));
		});
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
						LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 20)),
				new WeeklyProgramProposalResponse.Phase("Doğrula ve Sun", "Sunum ve 90 günlük plan", 9, 10,
						LocalDate.of(2026, 12, 22), LocalDate.of(2027, 1, 3)));
		assertThat(proposal.warnings()).containsExactly(
				new WeeklyProgramProposalResponse.Warning(WeeklyProgramWarning.DATES_DIFFER, List.of(8, 10)));
		assertThat(proposal.notes()).containsExactly("9 Ocak Cumartesi — Demo Day");
		assertThat(proposal.existing()).isEqualTo(new WeeklyProgramProposalResponse.Existing(0, 0,
				ProgramCalendar.currentWeek(START, END, LocalDate.now(MeetingSlotTimes.APP_ZONE))));
	}

	@Test
	void refusesBeforeReadingTheFileWhenSyllabusReadingIsSwitchedOff() {
		editing();
		WeeklyProgramService switchedOff = new WeeklyProgramService(coordinatorProgramService, programWeekPlanService,
				programPhaseRepository, programWeekRepository, storedDocumentRepository, objectStorage, textExtractor,
				piiRedactionService, aiProviderClient, TestAiFeatures.allOnExcept(AiFeature.SYLLABUS));

		assertThatThrownBy(() -> switchedOff.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(AiFeatureDisabledException.class);
		verifyNoInteractions(storedDocumentRepository, objectStorage, textExtractor, aiProviderClient);
	}

	@Test
	void weeksPastTheProgramAreDroppedAndFlaggedAndADatedRowWithoutANumberIsPlacedByItsDate() {
		JsonNode answer = json("""
				{"weeks": [
				  %s,
				  %s,
				  {"week": null, "source_date": "2026-11-04", "title": "Yalnızca tarihli", "topics": [],
				   "activities": [], "deliverables": [], "phase": "Problem"}
				], "phases": [], "notes": [], "warnings": []}
				""".formatted(week(1, null, "Tanışma", "Problem"), week(12, null, "Program dışı", "Problem")));

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

	/** A program printed one row per session (Monday, Wednesday, Friday) gives the same week number three times. */
	@Test
	void aWeekThatComesBackOncePerSessionIsMergedIntoOneWeek() {
		JsonNode answer = json("""
				{"weeks": [
				  {"week": 1, "source_date": "2026-10-27", "title": "Müşteri ve Pazar", "phase": "Keşif",
				   "topics": ["Yapılandırılmış eğitim", "Konuşmacı: Ayşe Örnek", "Kitap: Örnek Kitap"],
				   "activities": [], "deliverables": ["Müşteri profili"]},
				  {"week": 1, "source_date": "2026-10-29", "title": "Müşteri ve Pazar", "phase": null,
				   "topics": ["Uygulayıcı oturumu", "Konuşmacı: Mehmet Örnek"],
				   "activities": [], "deliverables": ["PMF hipotezi", "Müşteri profili"]},
				  {"week": 1, "source_date": "2026-10-31", "title": "Kurucu Atölyesi", "phase": null,
				   "topics": ["Konuşmacı: Zeynep Örnek"],
				   "activities": ["Workshop: Görüşme atölyesi"], "deliverables": ["5 müşteri görüşmesi"]},
				  %s
				], "phases": [], "notes": [], "warnings": []}
				""".formatted(week(2, "2026-11-03", "Problem", "Keşif")));

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.weeks()).extracting(ProgramWeekPlanResponse.Week::week).containsExactly(1, 2);
		ProgramWeekPlanResponse.Week first = proposal.weeks().get(0);
		assertThat(first.title()).isEqualTo("Müşteri ve Pazar");
		assertThat(first.topics()).containsExactly("Yapılandırılmış eğitim", "Konuşmacı: Ayşe Örnek",
				"Kitap: Örnek Kitap", "Uygulayıcı oturumu", "Konuşmacı: Mehmet Örnek", "Kurucu Atölyesi",
				"Konuşmacı: Zeynep Örnek");
		assertThat(first.activities()).containsExactly("Workshop: Görüşme atölyesi");
		assertThat(first.deliverables()).as("a task named twice is kept once")
				.containsExactly("Müşteri profili", "PMF hipotezi", "5 müşteri görüşmesi");
		assertThat(first.inferred()).isFalse();
		assertThat(proposal.warnings()).as("merging is not a loss, so nothing is flagged")
				.extracting(WeeklyProgramProposalResponse.Warning::code)
				.doesNotContain(WeeklyProgramWarning.WEEKS_DROPPED, WeeklyProgramWarning.DATES_DIFFER);
	}

	@Test
	void aMergedWeekKeepsAtMostTwelveLinesPerList() {
		List<String> rows = new ArrayList<>();
		for (int session = 1; session <= 5; session++) {
			rows.add("""
					{"week": 1, "source_date": null, "title": "Hafta 1", "phase": null, "activities": [],
					 "topics": ["Oturum %1$d", "Konuşmacı: Kişi %1$d", "Kitap: Kitap %1$d"], "deliverables": []}"""
					.formatted(session));
		}
		JsonNode answer = json("{\"weeks\": [" + String.join(",", rows) + "], \"phases\": [], \"notes\": [], "
				+ "\"warnings\": []}");

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.weeks().get(0).topics()).hasSize(12).startsWith("Oturum 1").endsWith("Kitap: Kitap 4");
	}

	@Test
	void aWeekNumberOutsideTheProgramIsPlacedByTheDateItPrints() {
		JsonNode answer = json("""
				{"weeks": [
				  {"week": 0, "source_date": "2026-10-27", "title": "Açılış Günü", "topics": [], "activities": [],
				   "deliverables": ["Yol haritası"], "phase": "Keşif"},
				  %s
				], "phases": [], "notes": [], "warnings": []}
				""".formatted(week(1, "2026-10-28", "Tanışma", "Keşif")));

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.weeks()).hasSize(1);
		ProgramWeekPlanResponse.Week first = proposal.weeks().get(0);
		assertThat(first.title()).as("the row with the printed number names the week").isEqualTo("Tanışma");
		assertThat(first.topics()).startsWith("Açılış Günü");
		assertThat(first.deliverables()).containsExactly("Yol haritası", "Haftalık görev");
		assertThat(first.inferred()).as("one of its rows carries the week's own number").isFalse();
		assertThat(proposal.warnings()).extracting(WeeklyProgramProposalResponse.Warning::code)
				.doesNotContain(WeeklyProgramWarning.WEEKS_DROPPED);
	}

	@Test
	void rowsTheCalendarCannotPlaceBecomeNotesInsteadOfDisappearing() {
		JsonNode answer = json("""
				{"weeks": [
				  {"week": null, "source_date": "2026-10-25", "title": "Ön Kamp", "topics": [], "activities": [],
				   "deliverables": [], "phase": null},
				  {"week": null, "source_date": null, "title": "Mezuniyet Töreni", "topics": [], "activities": [],
				   "deliverables": [], "phase": null},
				  {"week": null, "source_date": "2027-02-01", "title": "Demo Day", "topics": [], "activities": [],
				   "deliverables": [], "phase": null},
				  {"week": null, "source_date": null, "title": "", "topics": [], "activities": [], "deliverables": [],
				   "phase": null},
				  %s
				], "phases": [], "notes": ["Ağaç dikme günü"], "warnings": []}
				""".formatted(week(1, null, "Tanışma", "Keşif")));

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.weeks()).extracting(ProgramWeekPlanResponse.Week::title).containsExactly("Tanışma");
		assertThat(proposal.notes()).containsExactly("25.10.2026 — Ön Kamp", "Mezuniyet Töreni",
				"01.02.2027 — Demo Day", "Ağaç dikme günü");
		assertThat(proposal.warnings()).extracting(WeeklyProgramProposalResponse.Warning::code)
				.doesNotContain(WeeklyProgramWarning.WEEKS_DROPPED);
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
		when(storedDocumentRepository.findById(DOCUMENT_ID))
				.thenReturn(Optional.of(weeklyProgram(OTHER_COORDINATOR_ID)));
		assertThatThrownBy(() -> service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(ResourceNotFoundException.class);

		StoredDocument cv = weeklyProgram(COORDINATOR_ID);
		cv.setKind(DocumentKind.CV);
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(cv));
		assertThatThrownBy(() -> service.read(COORDINATOR_ID, PROGRAM_ID, readRequest()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(aiProviderClient, never()).weeklyProgram(anyString(), any(), any(), any());
	}

	@Test
	void theProgramsOwnFileCanBeReadAgainByACoCoordinator() {
		program.setSyllabusDocumentId(DOCUMENT_ID);
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID))
				.thenReturn(Optional.of(weeklyProgram(OTHER_COORDINATOR_ID)));
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
						"Problem tanımı", null),
				new ReplaceProgramPhasesRequest.PhaseInput("Fikir", LocalDate.of(2026, 11, 17), END, null, null)));
		verify(programWeekPlanService).writeWeeks(program, COORDINATOR_ID, weeks);
		verify(coordinatorProgramService).attachSyllabus(program, COORDINATOR_ID, DOCUMENT_ID);
	}

	@Test
	void aPlanThatChangedSinceTheReadIsNotOverwritten() {
		editing();
		when(programPhaseRepository.findByProgramIdOrderByPositionAsc(PROGRAM_ID))
				.thenReturn(List.of(new ProgramPhase()));
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
						new SaveWeeklyProgramRequest.PhaseInput("Pazar", null, 5, 6)),
				1, 3, true));

		@SuppressWarnings("unchecked") // ArgumentCaptor.forClass takes the raw collection class.
		ArgumentCaptor<List<ReplaceProgramPhasesRequest.PhaseInput>> phases = ArgumentCaptor.forClass(List.class);
		verify(coordinatorProgramService).writePhases(eq(PROGRAM_ID), phases.capture());
		assertThat(phases.getValue()).extracting(ReplaceProgramPhasesRequest.PhaseInput::name)
				.containsExactly("Eski Keşif", "Problem", "Pazar");
		assertThat(phases.getValue().get(1).startDate()).isEqualTo(today);
		@SuppressWarnings("unchecked") // ArgumentCaptor.forClass takes the raw collection class.
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

		@SuppressWarnings("unchecked") // ArgumentCaptor.forClass takes the raw collection class.
		ArgumentCaptor<List<ReplaceProgramWeeksRequest.WeekInput>> weeks = ArgumentCaptor.forClass(List.class);
		verify(programWeekPlanService).writeWeeks(eq(program), eq(COORDINATOR_ID), weeks.capture());
		assertThat(weeks.getValue()).extracting(ReplaceProgramWeeksRequest.WeekInput::title)
				.containsExactly("Eski 1", "Eski 9");
	}

	@Test
	void readingForAnEndedProgramReportsTheSameKeptWeeksTheSaveKeeps() {
		LocalDate today = LocalDate.now(MeetingSlotTimes.APP_ZONE);
		program.setStartDate(today.minusDays(70));
		program.setEndDate(today.minusDays(8));
		editing();
		when(storedDocumentRepository.findById(DOCUMENT_ID)).thenReturn(Optional.of(weeklyProgram(COORDINATOR_ID)));
		stubText("Hafta 1", "Hafta 1", 1);
		when(aiProviderClient.weeklyProgram(anyString(), any(), any(), any())).thenReturn(lookAlikeAnswer());

		WeeklyProgramProposalResponse proposal = service.read(COORDINATOR_ID, PROGRAM_ID, readRequest());

		assertThat(proposal.totalWeeks()).as("the dated PDF week beyond the configured length is retained")
				.isEqualTo(10);
		assertThat(proposal.existing().currentWeek())
				.as("every week is past, so a keep-past save keeps the last one too")
				.isEqualTo(10);
	}

	@Test
	void theFirstReplacedWeekIsNullBeforeTheStartTheRunningWeekThenOnePastTheEnd() {
		assertThat(WeeklyProgramService.firstReplacedWeek(START, END, START.minusDays(1))).isNull();
		assertThat(WeeklyProgramService.firstReplacedWeek(START, END, START)).isEqualTo(1);
		assertThat(WeeklyProgramService.firstReplacedWeek(START, END, END)).isEqualTo(11);
		assertThat(WeeklyProgramService.firstReplacedWeek(START, END, END.plusDays(1))).isEqualTo(12);
	}

	@Test
	void completedPhasesStayAndAPhaseRunningIntoTodayStartsAfterThem() {
		LocalDate today = LocalDate.of(2026, 11, 20);
		List<ReplaceProgramPhasesRequest.PhaseInput> kept = WeeklyProgramService.keepCompletedPhases(
				List.of(phase("Keşif", START, LocalDate.of(2026, 11, 9)),
						phase("Şimdiki", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30))),
				List.of(
						new ReplaceProgramPhasesRequest.PhaseInput("Yeni geçmiş", START, LocalDate.of(2026, 11, 2),
								null, null),
						new ReplaceProgramPhasesRequest.PhaseInput("Yeni şimdiki", LocalDate.of(2026, 11, 3),
								LocalDate.of(2026, 11, 23), null, null),
						new ReplaceProgramPhasesRequest.PhaseInput("Yeni sonraki", LocalDate.of(2026, 11, 24),
								LocalDate.of(2026, 12, 7), null, null)),
				today);

		assertThat(kept).containsExactly(
				new ReplaceProgramPhasesRequest.PhaseInput("Keşif", START, LocalDate.of(2026, 11, 9), null, null),
				new ReplaceProgramPhasesRequest.PhaseInput("Yeni şimdiki", LocalDate.of(2026, 11, 10),
						LocalDate.of(2026, 11, 23), null, null),
				new ReplaceProgramPhasesRequest.PhaseInput("Yeni sonraki", LocalDate.of(2026, 11, 24),
						LocalDate.of(2026, 12, 7), null, null));
	}

	@Test
	void anUnplacedRowIsKeptWhenTheAiFillsTheNotes() {
		List<String> aiNotes = new ArrayList<>();
		for (int note = 1; note <= 10; note++) {
			aiNotes.add("\"Not " + note + "\"");
		}
		JsonNode answer = json("""
				{"weeks": [{"week": null, "source_date": null, "title": "Mezuniyet Töreni", "topics": [],
				  "activities": [], "deliverables": [], "phase": null}],
				 "phases": [], "notes": [%s], "warnings": []}
				""".formatted(String.join(",", aiNotes)));

		WeeklyProgramProposalResponse proposal = WeeklyProgramService.proposalOf(answer, START, END, 11,
				List.of(), 1);

		assertThat(proposal.notes()).hasSize(10).startsWith("Mezuniyet Töreni");
	}

	private void editing() {
		when(coordinatorProgramService.requireSettingsEditor(COORDINATOR_ID, PROGRAM_ID)).thenReturn(program);
	}

	private void stubText(String raw, String redacted, int pages) {
		when(objectStorage.require("weekly-key")).thenReturn(new byte[] { 1 });
		when(textExtractor.extract(any(), eq(OcrMode.AUTO)))
				.thenReturn(new ExtractionResult(raw, pages, List.of(), false));
		when(piiRedactionService.redact(raw)).thenReturn(new RedactionResult(redacted, Map.of(), 0));
	}

	private static ReadWeeklyProgramRequest readRequest() {
		return new ReadWeeklyProgramRequest(null, DOCUMENT_ID);
	}

	private static StoredDocument weeklyProgram(UUID ownerId) {
		StoredDocument document = new StoredDocument();
		document.setId(DOCUMENT_ID);
		document.setUserId(ownerId);
		document.setKind(DocumentKind.WEEKLY_PROGRAM);
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
