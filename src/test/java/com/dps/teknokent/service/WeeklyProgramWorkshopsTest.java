package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;

class WeeklyProgramWorkshopsTest {

	private static final LocalDate MONDAY = LocalDate.of(2026, 10, 26);
	private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 28);
	private static final LocalDate PROGRAM_START = MONDAY;
	private static final LocalDate PROGRAM_END = LocalDate.of(2027, 1, 9);
	private static final LocalDate LAST_MONDAY = LocalDate.of(2026, 12, 28);

	@Test
	void anUndatedWorkshopIsHeldTwoDaysAfterTheWeeksPrintedStart() {
		var week = week(List.of("Workshop: Helin & Çağrı"), session(MONDAY, "Girişimcilik Mindset"));

		var placed = placed(week, MONDAY);

		assertThat(placed.sessions())
				.extracting(ProgramWeekPlanResponse.Session::date, ProgramWeekPlanResponse.Session::title)
				.containsExactly(tuple(MONDAY, "Girişimcilik Mindset"), tuple(WEDNESDAY, "Workshop: Helin & Çağrı"));
		assertThat(placed.sessions().getLast()).satisfies(session -> {
			assertThat(session.startTime()).isNull();
			assertThat(session.endTime()).isNull();
			assertThat(session.place()).isNull();
		});
		assertThat(placed.activities()).containsExactly("Workshop: Helin & Çağrı");
	}

	@ParameterizedTest
	@ValueSource(strings = { "Workshop: Naim Zaheir – 12 Kasım Perşembe, Build ile ortak",
			"Workshop: İş Modeli Tasarımı – Selen Kuruş (09.12.2026, yüz yüze)",
			"Workshop: İş Modeli ve Gelir Modelleri – Cemal Atakan Parlak (3 Aralık)",
			"Workshop: Deniz Erbil – 4 OCAK Pazartesi", "Workshop: Retro (16.12)", "Workshop: Retro 2026-12-16",
			"Workshop: Guest speaker, 3 December", "Workshop: Sarper, Çarşamba", "Workshop: Retro – CUMARTESİ" })
	void aWorkshopThatPrintsItsOwnDateKeepsTheModelsSessionAlone(String line) {
		var week = week(List.of(line), session(LocalDate.of(2026, 11, 12), "Naim Zaheir"));

		assertThat(placed(week, MONDAY)).isSameAs(week);
	}

	@Test
	void anUndatedWorkshopBesideADatedSpeakerSessionStillGetsItsDay() {
		var week = weekFrom(LAST_MONDAY, List.of("Ziyaret: 4 Ocak Pazartesi, Deniz Erbil", "Workshop: Sarper"),
				session(LocalDate.of(2027, 1, 4), "Deniz Erbil"));

		assertThat(placed(week, LAST_MONDAY).sessions())
				.extracting(ProgramWeekPlanResponse.Session::date, ProgramWeekPlanResponse.Session::title)
				.containsExactly(tuple(LocalDate.of(2026, 12, 30), "Workshop: Sarper"),
						tuple(LocalDate.of(2027, 1, 4), "Deniz Erbil"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "Workshop: Pazar araştırması", "Workshop: Sarper, 2.5 saat", "Workshop: Sarper (1.5 sa)" })
	void aMarketOrADurationIsNotTheCellsOwnDate(String line) {
		var week = week(List.of(line));

		assertThat(placed(week, MONDAY).sessions()).extracting(ProgramWeekPlanResponse.Session::date)
				.containsExactly(WEDNESDAY);
	}

	@Test
	void twoUndatedWorkshopLinesInOneWeekBothGetTheWorkshopDay() {
		var week = week(List.of("Workshop: Helin & Çağrı", "Ziyaret: CoZone", "Workshop: Sarper"));

		assertThat(placed(week, MONDAY).sessions())
				.extracting(ProgramWeekPlanResponse.Session::date, ProgramWeekPlanResponse.Session::title)
				.containsExactly(tuple(WEDNESDAY, "Workshop: Helin & Çağrı"), tuple(WEDNESDAY, "Workshop: Sarper"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "Workshop: –", "Workshop: -", "Workshop:", "Ziyaret: CoZone", "Atölye: Sarper" })
	void anEmptyWorkshopCellOrAnotherActivityAddsNothing(String line) {
		var week = week(List.of(line));

		assertThat(placed(week, MONDAY).sessions()).isEmpty();
	}

	@Test
	void aWorkshopDayThatAlreadyHasASessionIsNotDuplicated() {
		// Growth Circuit prints every session with its own date, Wednesday included.
		var week = week(List.of("Workshop: Sertaç Yakın"), session(MONDAY, "Yapılandırılmış eğitim"),
				session(WEDNESDAY, "Uygulayıcı / gerçek vaka"), session(LocalDate.of(2026, 10, 30), "Atölye"));

		assertThat(placed(week, MONDAY)).isSameAs(week);
	}

	@Test
	void aWeekThatPrintsAnEndBeforeTheWorkshopDayGetsNoGuessedWorkshop() {
		var week = new ProgramWeekPlanResponse.Week(1, MONDAY, MONDAY.plusDays(1), true, "İki günlük kamp", List.of(),
				List.of("Workshop: Sarper"), List.of(), false, List.of());

		assertThat(placed(week, MONDAY)).isSameAs(week);
	}

	@Test
	void theWorkshopTakesItsPlaceAmongTheWeeksSessionsByDate() {
		var week = weekFrom(LAST_MONDAY, List.of("workshop: Sarper"), session(LocalDate.of(2027, 1, 4), "Deniz Erbil"));

		var placed = placed(week, LAST_MONDAY);

		assertThat(placed.sessions()).extracting(ProgramWeekPlanResponse.Session::date)
				.containsExactly(LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 4));
	}

	@Test
	void aWeekAtItsSessionLimitGetsNoMoreSessions() {
		var full = IntStream.range(0, ReplaceProgramWeeksRequest.MAX_SESSIONS)
				.mapToObj(day -> session(MONDAY.minusDays(day + 1L), "Oturum " + day)).toList();
		var week = new ProgramWeekPlanResponse.Week(1, MONDAY, MONDAY.plusDays(6), true, "Hafta", List.of(),
				List.of("Workshop: Sarper"), List.of(), false, full);

		assertThat(placed(week, MONDAY)).isSameAs(week);
	}

	@Test
	void aLongWorkshopCellIsCutToTheSessionTitleLimit() {
		var week = week(List.of("Workshop: " + "x".repeat(190)));

		assertThat(placed(week, MONDAY).sessions()).singleElement()
				.satisfies(session -> assertThat(session.title()).hasSize(160).startsWith("Workshop: "));
	}

	@Test
	void aWeekStartReadWithTheWrongYearGetsNoWorkshopOutsideTheProgram() {
		LocalDate misread = LocalDate.of(2021, 12, 21);
		var week = new ProgramWeekPlanResponse.Week(9, misread, misread.plusDays(6), true, "Go-to-Market", List.of(),
				List.of("Workshop: Emre Çetinarslan"), List.of(), false,
				List.of(session(LocalDate.of(2026, 12, 21), "Satış & Pazarlama")));

		assertThat(placed(week, misread)).isSameAs(week);
	}

	@Test
	void aWorkshopDayAfterTheProgramEndsIsNotGuessed() {
		LocalDate lastWeek = LocalDate.of(2027, 1, 8);
		var week = new ProgramWeekPlanResponse.Week(11, lastWeek, lastWeek.plusDays(6), true, "Son", List.of(),
				List.of("Workshop: Sarper"), List.of(), false, List.of());

		assertThat(placed(week, lastWeek)).isSameAs(week);
	}

	private static ProgramWeekPlanResponse.Week placed(ProgramWeekPlanResponse.Week week, LocalDate printedStart) {
		return WeeklyProgramWorkshops.placed(week, printedStart, PROGRAM_START, PROGRAM_END);
	}

	private static ProgramWeekPlanResponse.Week week(List<String> activities,
			ProgramWeekPlanResponse.Session... sessions) {
		return weekFrom(MONDAY, activities, sessions);
	}

	private static ProgramWeekPlanResponse.Week weekFrom(LocalDate start, List<String> activities,
			ProgramWeekPlanResponse.Session... sessions) {
		return new ProgramWeekPlanResponse.Week(1, start, start.plusDays(6), true, "Hafta", List.of(), activities,
				List.of(), false, List.of(sessions));
	}

	private static ProgramWeekPlanResponse.Session session(LocalDate date, String title) {
		return new ProgramWeekPlanResponse.Session(date, null, null, null, title);
	}
}
