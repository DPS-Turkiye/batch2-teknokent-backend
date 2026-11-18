package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.ProgramCalendarEntryKind;
import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramEvent;
import com.dps.teknokent.entity.ProgramSpecialDay;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;
import com.dps.teknokent.repository.ProgramSpecialDayRepository;
import com.dps.teknokent.repository.ProgramWeekRepository;
import com.dps.teknokent.service.ProgramPlanCalendarService.Entry;

/** Shaped on Build: weeks start on Monday, the lesson that day and the workshop two days later. */
@ExtendWith(MockitoExtension.class)
class ProgramPlanCalendarServiceTest {

	private static final UUID PROGRAM_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final LocalDate WEEK_THREE = LocalDate.of(2026, 11, 9);
	private static final LocalDate WEEK_FOUR = LocalDate.of(2026, 11, 16);
	private static final LocalDate WEEK_FIVE = LocalDate.of(2026, 11, 23);
	// 1 Nov to 1 Feb in Istanbul: wide enough to hold every entry of the plan.
	private static final Instant FROM = Instant.parse("2026-10-31T21:00:00Z");
	private static final Instant TO = Instant.parse("2027-01-31T21:00:00Z");

	@Mock
	private ProgramWeekRepository programWeekRepository;

	@Mock
	private ProgramSpecialDayRepository programSpecialDayRepository;

	@InjectMocks
	private ProgramPlanCalendarService service;

	private final Program program = program();
	private final List<ProgramWeek> plan = new ArrayList<>();
	private final List<ProgramSpecialDay> specialDays = new ArrayList<>();

	@BeforeEach
	void build() {
		ProgramWeek three = week(3, WEEK_THREE, "ZİYARET / GEZİ 1", "Konuşmacı: Naim Zaheir");
		three.getSessions().add(session(WEEK_THREE.plusDays(3), "Naim Zaheir Ziyaret"));
		ProgramWeek four = week(4, WEEK_FOUR, "Sorunu Doğrula", "Değer Önerisi ve Market Fit", "Konuşmacı: Sarper");
		four.getSessions().add(session(WEEK_FOUR, "Değer Önerisi ve Market Fit"));
		four.getSessions().add(session(WEEK_FOUR.plusDays(2), "Workshop: Burcu Tarhan"));
		ProgramWeek five = week(5, WEEK_FIVE, "Pazarı Anla", "Konuşmacı: İsa Özdemir");
		five.getSessions().add(session(WEEK_FIVE, "Pazar ve Rakip Analizi"));
		plan.addAll(List.of(five, three, four));
		specialDays.add(specialDay(LocalDate.of(2027, 1, 9), "DEMO DAY"));
		lenient().when(programWeekRepository.findByProgramIdIn(anyCollection())).thenReturn(plan);
		lenient().when(programSpecialDayRepository.findByProgramIdInAndDateBetweenOrderByDateAsc(anyCollection(),
				any(), any())).thenReturn(specialDays);
	}

	@Test
	void theDayBeforeAWeekStartsNoneOfItsSessionsAreShownToAParticipant() {
		List<Entry> entries = service.forParticipants(List.of(program), WEEK_FOUR.minusDays(1), FROM, TO, List.of());

		assertThat(sessionTitles(entries)).containsExactly("Naim Zaheir Ziyaret");
	}

	@Test
	void onTheDayAWeekStartsItsLessonAndItsWorkshopTwoDaysLaterAreShownButNotTheNextWeek() {
		List<Entry> entries = service.forParticipants(List.of(program), WEEK_FOUR, FROM, TO, List.of());

		assertThat(sessionTitles(entries))
				.containsExactly("Naim Zaheir Ziyaret", "Değer Önerisi ve Market Fit", "Workshop: Burcu Tarhan");
	}

	@Test
	void aSpecialDayIsShownToAParticipantLongBeforeItsWeek() {
		List<Entry> entries = service.forParticipants(List.of(program), WEEK_THREE, FROM, TO, List.of());

		assertThat(entries).filteredOn(entry -> entry.kind() == ProgramCalendarEntryKind.SPECIAL_DAY)
				.singleElement()
				.satisfies(entry -> {
					assertThat(entry.title()).isEqualTo("DEMO DAY");
					assertThat(entry.startsAt()).isEqualTo(Instant.parse("2027-01-09T10:00:00Z"));
					assertThat(entry.plan().place()).isEqualTo("KKM");
					assertThat(entry.plan().details()).containsExactly("Program final etkinliği.");
					assertThat(entry.plan().week()).isNull();
				});
	}

	@Test
	void theCoordinatorSeesEverySessionWhateverTheDate() {
		List<Entry> entries = service.forCoordinator(List.of(program), FROM, TO, List.of());

		assertThat(sessionTitles(entries)).containsExactly("Naim Zaheir Ziyaret", "Değer Önerisi ve Market Fit",
				"Workshop: Burcu Tarhan", "Pazar ve Rakip Analizi");
		assertThat(entries).extracting(Entry::kind).last().isEqualTo(ProgramCalendarEntryKind.SPECIAL_DAY);
	}

	@Test
	void theLessonCarriesTheWeeksSpeakerAndTheWorkshopDoesNot() {
		List<Entry> entries = service.forCoordinator(List.of(program), FROM, TO, List.of());

		Entry lesson = byTitle(entries, "Değer Önerisi ve Market Fit");
		assertThat(lesson.plan().week()).isEqualTo(4);
		assertThat(lesson.plan().weekTitle()).isEqualTo("Sorunu Doğrula");
		assertThat(lesson.plan().speaker()).isEqualTo("Sarper");
		assertThat(lesson.plan().allDay()).isTrue();
		assertThat(lesson.startsAt()).isEqualTo(Instant.parse("2026-11-15T21:00:00Z"));
		assertThat(lesson.endsAt()).isNull();
		assertThat(byTitle(entries, "Workshop: Burcu Tarhan").plan().speaker()).isNull();
		// Week 3's visit is not on the week's first day, so it is not the speaker's lesson.
		assertThat(byTitle(entries, "Naim Zaheir Ziyaret").plan().speaker()).isNull();
	}

	@Test
	void aTimedSessionStartsAndEndsAtItsIstanbulTimesAndKeepsItsPlace() {
		ProgramWeekSession timed = session(WEEK_FIVE.plusDays(1), "Kanıt ve Sahne");
		timed.setStartTime(LocalTime.of(20, 0));
		timed.setEndTime(LocalTime.of(22, 0));
		timed.setPlace(" Online ");
		plan.getFirst().getSessions().add(timed);

		Entry entry = byTitle(service.forCoordinator(List.of(program), FROM, TO, List.of()), "Kanıt ve Sahne");

		assertThat(entry.startsAt()).isEqualTo(Instant.parse("2026-11-24T17:00:00Z"));
		assertThat(entry.endsAt()).isEqualTo(Instant.parse("2026-11-24T19:00:00Z"));
		assertThat(entry.plan().place()).isEqualTo("Online");
		assertThat(entry.plan().allDay()).isFalse();
	}

	@Test
	void aSessionWithoutATitleTakesItsWeeksTitle() {
		plan.getFirst().getSessions().add(session(WEEK_FIVE.plusDays(2), "  "));

		List<Entry> entries = service.forCoordinator(List.of(program), FROM, TO, List.of());

		assertThat(sessionTitles(entries)).contains("Pazarı Anla");
	}

	@Test
	void anEntryThatRepeatsACoordinatorsEventOnTheSameDayIsLeftOutAndTheEventStays() {
		List<ProgramEvent> events = List.of(
				event("  workshop:  burcu TARHAN ", Instant.parse("2026-11-18T12:00:00Z")),
				event("Demo Day", Instant.parse("2027-01-09T09:00:00Z")),
				// Same title on another day is another thing.
				event("Pazar ve Rakip Analizi", Instant.parse("2026-11-24T09:00:00Z")));

		List<Entry> entries = service.forCoordinator(List.of(program), FROM, TO, events);

		assertThat(entries).extracting(Entry::title).doesNotContain("Workshop: Burcu Tarhan", "DEMO DAY")
				.contains("Pazar ve Rakip Analizi");
	}

	@Test
	void aSessionThatRepeatsASpecialDayIsLeftOut() {
		ProgramWeek ten = week(10, LocalDate.of(2027, 1, 4), "Demo Haftası");
		ten.getSessions().add(session(LocalDate.of(2027, 1, 9), "Demo Day"));
		plan.add(ten);

		List<Entry> entries = service.forCoordinator(List.of(program), FROM, TO, List.of());

		assertThat(entries).filteredOn(entry -> entry.startsAt().equals(Instant.parse("2027-01-09T10:00:00Z")))
				.singleElement()
				.satisfies(entry -> assertThat(entry.kind()).isEqualTo(ProgramCalendarEntryKind.SPECIAL_DAY));
		assertThat(sessionTitles(entries)).doesNotContain("Demo Day");
	}

	@Test
	void anotherProgramsEventOfTheSameTitleAndDayHidesNothing() {
		ProgramEvent elsewhere = event("Workshop: Burcu Tarhan", Instant.parse("2026-11-18T12:00:00Z"));
		elsewhere.setProgramId(UUID.randomUUID());

		List<Entry> entries = service.forCoordinator(List.of(program), FROM, TO, List.of(elsewhere));

		assertThat(sessionTitles(entries)).contains("Workshop: Burcu Tarhan");
	}

	@Test
	void twoSpecialDaysOfOneDayWithTheSameTitleAreBothShown() {
		specialDays.add(specialDay(LocalDate.of(2027, 1, 9), "DEMO DAY"));

		List<Entry> entries = service.forParticipants(List.of(program), WEEK_THREE, FROM, TO, List.of());

		assertThat(entries).filteredOn(entry -> entry.kind() == ProgramCalendarEntryKind.SPECIAL_DAY).hasSize(2);
	}

	@Test
	void twoTimedSessionsOfOneDaySharingATitleAreBothShownWithIdsOfTheirOwn() {
		ProgramWeekSession morning = session(WEEK_FIVE.plusDays(2), "Mentor saati");
		morning.setStartTime(LocalTime.of(10, 0));
		ProgramWeekSession afternoon = session(WEEK_FIVE.plusDays(2), "Mentor saati");
		afternoon.setStartTime(LocalTime.of(14, 0));
		plan.getFirst().getSessions().addAll(List.of(morning, afternoon));

		List<Entry> twins = service.forCoordinator(List.of(program), FROM, TO, List.of()).stream()
				.filter(entry -> entry.title().equals("Mentor saati")).toList();

		assertThat(twins).extracting(Entry::startsAt).containsExactly(Instant.parse("2026-11-25T07:00:00Z"),
				Instant.parse("2026-11-25T11:00:00Z"));
		assertThat(twins).extracting(Entry::id).doesNotHaveDuplicates();
	}

	// A plan that prints no dates has the program calendar's Monday as its week start, which need not be a session day.
	@Test
	void inAWeekWithoutPrintedDatesTheSpeakerGoesWithItsFirstSession() {
		ProgramWeek undated = week(6, WEEK_FIVE.plusDays(7), "Finansal Model", "Konuşmacı: Ayşe Kaya");
		undated.setStartDate(null);
		undated.setEndDate(null);
		undated.getSessions().add(session(LocalDate.of(2026, 12, 3), "Workshop: Nakit akışı"));
		undated.getSessions().add(session(LocalDate.of(2026, 12, 1), "Birim ekonomisi"));
		Program withoutPrintedDates = program();
		withoutPrintedDates.setStartDate(LocalDate.of(2026, 10, 19));
		plan.clear();
		plan.add(undated);

		List<Entry> entries = service.forCoordinator(List.of(withoutPrintedDates), FROM, TO, List.of());

		assertThat(byTitle(entries, "Birim ekonomisi").plan().speaker()).isEqualTo("Ayşe Kaya");
		assertThat(byTitle(entries, "Workshop: Nakit akışı").plan().speaker()).isNull();
	}

	@Test
	void anAllDaySessionCountsWhileAnyPartOfItsDayIsInRange() {
		Instant noonOfTheWorkshop = Instant.parse("2026-11-18T09:00:00Z");
		Instant endOfTheWeek = Instant.parse("2026-11-22T20:59:59Z");

		List<Entry> entries = service.forCoordinator(List.of(program), noonOfTheWorkshop, endOfTheWeek, List.of());

		assertThat(sessionTitles(entries)).containsExactly("Workshop: Burcu Tarhan");
	}

	@Test
	void aSessionKeepsItsIdBetweenReadsAndTwinSessionsGetTheirOwn() {
		plan.getFirst().getSessions().add(session(WEEK_FIVE, "Pazar ve Rakip Analizi"));

		List<UUID> first = service.forCoordinator(List.of(program), FROM, TO, List.of()).stream().map(Entry::id)
				.toList();
		assertThat(sessionTitles(service.forCoordinator(List.of(program), FROM, TO, List.of())))
				.filteredOn("Pazar ve Rakip Analizi"::equals).hasSize(2);
		List<UUID> second = service.forCoordinator(List.of(program), FROM, TO, List.of()).stream().map(Entry::id)
				.toList();

		assertThat(first).isEqualTo(second).doesNotHaveDuplicates();
	}

	@Test
	void noProgramsMeansNoEntriesAndNoReads() {
		assertThat(service.forParticipants(List.of(), WEEK_FOUR, FROM, TO, List.of())).isEmpty();
		verifyNoInteractions(programWeekRepository, programSpecialDayRepository);
	}

	@Test
	void theSpeakerLineIsFoundInAnyCaseAndInEnglish() {
		assertThat(ProgramPlanCalendarService.speakerOf(List.of("Kitap: Sprint", "KONUŞMACI:  Sarper & Nihan ")))
				.isEqualTo("Sarper & Nihan");
		assertThat(ProgramPlanCalendarService.speakerOf(List.of("Speaker: Jane Doe"))).isEqualTo("Jane Doe");
		assertThat(ProgramPlanCalendarService.speakerOf(List.of("Konuşmacılar: Ada, Can"))).isEqualTo("Ada, Can");
		assertThat(ProgramPlanCalendarService.speakerOf(List.of("Konuşmacı:"))).isNull();
		assertThat(ProgramPlanCalendarService.speakerOf(List.of("Film: The Inventor"))).isNull();
	}

	@Test
	void titlesMatchAcrossTheTurkishDottedAndDotlessI() {
		LocalDate day = WEEK_FOUR;
		assertThat(ProgramPlanCalendarService.key(PROGRAM_ID, day, "KAPANIŞ İÇİN"))
				.isEqualTo(ProgramPlanCalendarService.key(PROGRAM_ID, day, "kapanış için"));
	}

	private static List<String> sessionTitles(List<Entry> entries) {
		return entries.stream().filter(entry -> entry.kind() == ProgramCalendarEntryKind.SESSION)
				.map(Entry::title).toList();
	}

	private static Entry byTitle(List<Entry> entries, String title) {
		return entries.stream().filter(entry -> entry.title().equals(title)).findFirst().orElseThrow();
	}

	private static Program program() {
		Program program = new Program();
		program.setId(PROGRAM_ID);
		program.setName("Build");
		program.setStartDate(LocalDate.of(2026, 10, 26));
		program.setEndDate(LocalDate.of(2027, 1, 9));
		return program;
	}

	private static ProgramWeek week(int number, LocalDate start, String title, String... topics) {
		ProgramWeek week = new ProgramWeek();
		week.setId(UUID.randomUUID());
		week.setProgramId(PROGRAM_ID);
		week.setWeekNumber(number);
		week.setTitle(title);
		week.setStartDate(start);
		week.setEndDate(start.plusDays(6));
		week.setTopics(ProgramWeek.joined(List.of(topics)));
		return week;
	}

	private static ProgramWeekSession session(LocalDate date, String title) {
		return new ProgramWeekSession(date, null, null, null, title);
	}

	private static ProgramSpecialDay specialDay(LocalDate date, String title) {
		ProgramSpecialDay day = new ProgramSpecialDay();
		day.setId(UUID.randomUUID());
		day.setProgramId(PROGRAM_ID);
		day.setDate(date);
		day.setStartTime(LocalTime.of(13, 0));
		day.setPlace("KKM");
		day.setTitle(title);
		day.setDetails("Program final etkinliği.");
		return day;
	}

	private static ProgramEvent event(String title, Instant startsAt) {
		ProgramEvent event = new ProgramEvent();
		event.setId(UUID.randomUUID());
		event.setProgramId(PROGRAM_ID);
		event.setTitle(title);
		event.setKind(ProgramEventKind.EVENT);
		event.setStartsAt(startsAt);
		return event;
	}
}
