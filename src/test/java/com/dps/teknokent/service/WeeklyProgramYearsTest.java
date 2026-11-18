package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeeklyProgramYearsTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	/** Monday weeks from 26 October 2026, a program that runs into January 2027. */
	private static final LocalDate START = LocalDate.of(2026, 10, 26);
	private static final LocalDate END = LocalDate.of(2027, 1, 9);

	@Test
	void aWeekStartReadWithTheWrongYearTakesTheYearThatFitsItsNeighbours() {
		JsonNode answer = weeks("2026-12-14", "2021-12-21", "2026-12-28");

		assertThat(starts(WeeklyProgramYears.corrected(answer, START, END)))
				.containsExactly("2026-12-14", "2026-12-21", "2026-12-28");
	}

	@Test
	void eachSideOfTheNewYearKeepsItsOwnYear() {
		JsonNode right = weeks("2026-12-21", "2026-12-28", "2027-01-04");
		JsonNode misread = weeks("2026-12-21", "2027-12-28", "2026-01-04");

		assertThat(WeeklyProgramYears.corrected(right, START, END)).isEqualTo(right);
		assertThat(starts(WeeklyProgramYears.corrected(misread, START, END)))
				.containsExactly("2026-12-21", "2026-12-28", "2027-01-04");
	}

	@Test
	void aDateNoYearPutsInsideTheProgramIsLeftAlone() {
		// A kick-off in September is before the program in any year; a demo day in March is after it.
		JsonNode answer = weeks("2026-09-15", "2026-10-26", "2026-11-02", "2027-03-01");

		assertThat(WeeklyProgramYears.corrected(answer, START, END)).isEqualTo(answer);
	}

	@Test
	void aCorrectedYearThatBreaksTheOrderOfTheWeeksIsNotTaken() {
		// 21 December fits the program, but not between 2 and 9 November: the date is wrong for another reason.
		JsonNode answer = weeks("2026-11-02", "2021-12-21", "2026-11-09");

		assertThat(WeeklyProgramYears.corrected(answer, START, END)).isEqualTo(answer);
	}

	@Test
	void missingAndUnreadableDatesAreLeftAsTheyCame() {
		JsonNode answer = JSON.readTree("""
				{"weeks": [{"week": 1, "source_date": null}, {"week": 2}, {"week": 3, "source_date": "21.12.2021"},
				  {"week": 4, "sessions": [{"title": "Demo"}, {"date": "2021-11-18", "title": "Ders"}]}, "not a week"],
				 "special_days": [{"title": "Demo Day", "date": null}, {"title": "Gezi"}]}
				""");

		JsonNode corrected = WeeklyProgramYears.corrected(answer, START, END);

		assertThat(corrected.path("weeks").get(3).path("sessions").get(1).path("date").asString())
				.as("a session in a week without a printed start is held to the program alone")
				.isEqualTo("2026-11-18");
		assertThat(corrected.path("weeks").get(0)).isEqualTo(answer.path("weeks").get(0));
		assertThat(corrected.path("weeks").get(1)).isEqualTo(answer.path("weeks").get(1));
		assertThat(corrected.path("weeks").get(2)).isEqualTo(answer.path("weeks").get(2));
		assertThat(corrected.path("weeks").get(4)).isEqualTo(answer.path("weeks").get(4));
		assertThat(corrected.path("special_days")).isEqualTo(answer.path("special_days"));
	}

	@Test
	void anAnswerWithoutWeeksComesBackAsItWas() {
		JsonNode empty = JSON.readTree("{}");
		JsonNode notAnObject = JSON.readTree("[]");

		assertThat(WeeklyProgramYears.corrected(empty, START, END)).isEqualTo(empty);
		assertThat(WeeklyProgramYears.corrected(notAnObject, START, END)).isSameAs(notAnObject);
	}

	@Test
	void theModelsAnswerItselfIsNotChanged() {
		JsonNode answer = weeks("2026-12-14", "2021-12-21");

		WeeklyProgramYears.corrected(answer, START, END);

		assertThat(starts(answer)).containsExactly("2026-12-14", "2021-12-21");
	}

	@Test
	void aWeeksEndSessionsAndSpecialDaysReadWithTheWrongYearAreFixedToo() {
		JsonNode answer = JSON.readTree("""
				{"weeks": [{"week": 9, "source_date": "2021-12-21", "source_end_date": "2021-12-27",
				  "sessions": [{"date": "2021-12-23", "title": "Workshop"}, {"date": "2026-12-21", "title": "Ders"}]}],
				 "special_days": [{"date": "2026-01-09", "title": "Demo Day"}]}
				""");

		JsonNode corrected = WeeklyProgramYears.corrected(answer, START, END);

		JsonNode week = corrected.path("weeks").get(0);
		assertThat(week.path("source_date").asString()).isEqualTo("2026-12-21");
		assertThat(week.path("source_end_date").asString()).isEqualTo("2026-12-27");
		assertThat(week.path("sessions").get(0).path("date").asString()).isEqualTo("2026-12-23");
		assertThat(week.path("sessions").get(1).path("date").asString()).isEqualTo("2026-12-21");
		assertThat(corrected.path("special_days").get(0).path("date").asString()).isEqualTo("2027-01-09");
	}

	@Test
	void aSessionIsOnlyMovedToAYearThatKeepsItNearItsOwnWeek() {
		// 30 December fits the program but is weeks past a week of 2 November.
		JsonNode answer = JSON.readTree("""
				{"weeks": [{"week": 2, "source_date": "2026-11-02",
				  "sessions": [{"date": "2025-12-30", "title": "Workshop"}, {"date": "2025-11-04", "title": "Ders"}]}]}
				""");

		JsonNode sessions = WeeklyProgramYears.corrected(answer, START, END).path("weeks").get(0).path("sessions");

		assertThat(sessions.get(0).path("date").asString()).isEqualTo("2025-12-30");
		assertThat(sessions.get(1).path("date").asString()).isEqualTo("2026-11-04");
	}

	@Test
	void aWeeksEndIsNotMovedBeforeItsStart() {
		JsonNode answer = JSON.readTree("""
				{"weeks": [{"week": 3, "source_date": "2026-11-09", "source_end_date": "2027-11-02"}]}
				""");

		assertThat(WeeklyProgramYears.corrected(answer, START, END)).isEqualTo(answer);
	}

	@Test
	void aDateTakesTheOnlyYearThatPutsItInTheRange() {
		assertThat(WeeklyProgramYears.withYearIn(LocalDate.of(2021, 12, 21), START, END))
				.isEqualTo(LocalDate.of(2026, 12, 21));
		assertThat(WeeklyProgramYears.withYearIn(LocalDate.of(2026, 1, 4), START, END))
				.isEqualTo(LocalDate.of(2027, 1, 4));
		assertThat(WeeklyProgramYears.withYearIn(LocalDate.of(2026, 9, 15), START, END)).isNull();
	}

	@Test
	void aDateThatFitsMoreThanOneYearOfALongRangeIsNotGuessed() {
		assertThat(WeeklyProgramYears.withYearIn(LocalDate.of(2021, 12, 21), START, START.plusYears(2))).isNull();
	}

	@Test
	void theTwentyNinthOfFebruaryOnlyMovesToALeapYear() {
		LocalDate leapDay = LocalDate.of(2024, 2, 29);

		assertThat(WeeklyProgramYears.withYearIn(leapDay, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31)))
				.isNull();
		assertThat(WeeklyProgramYears.withYearIn(leapDay, LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31)))
				.isEqualTo(LocalDate.of(2028, 2, 29));
	}

	@Test
	void anEmptyRangeHasNoYear() {
		assertThat(WeeklyProgramYears.withYearIn(LocalDate.of(2021, 12, 21), END, START)).isNull();
	}

	@Test
	void theFirstAndLastWeeksAreFixedAgainstTheirOneNeighbour() {
		assertThat(starts(WeeklyProgramYears.corrected(weeks("2021-10-26", "2026-11-02"), START, END)))
				.containsExactly("2026-10-26", "2026-11-02");
		assertThat(starts(WeeklyProgramYears.corrected(weeks("2026-12-21", "2021-12-28"), START, END)))
				.containsExactly("2026-12-21", "2026-12-28");
	}

	@Test
	void twoMisreadWeeksInARowAreBothFixedInOrder() {
		JsonNode answer = weeks("2026-12-07", "2021-12-14", "2020-12-21", "2026-12-28");

		assertThat(starts(WeeklyProgramYears.corrected(answer, START, END)))
				.containsExactly("2026-12-07", "2026-12-14", "2026-12-21", "2026-12-28");
	}

	@Test
	void aDateWithinAWeekOfTheProgramCountsAsInsideIt() {
		JsonNode weekBefore = weeks("2026-10-19", "2026-10-26");
		JsonNode eightDaysBefore = weeks("2025-10-18", "2026-10-26");

		assertThat(WeeklyProgramYears.corrected(weekBefore, START, END)).isEqualTo(weekBefore);
		assertThat(WeeklyProgramYears.corrected(eightDaysBefore, START, END)).isEqualTo(eightDaysBefore);
	}

	@Test
	void aMisreadWeekEndIsFixedOnlyWithinAWeekOfItsWeek() {
		JsonNode answer = JSON.readTree("""
				{"weeks": [{"week": 9, "source_date": "2026-12-21", "source_end_date": "2021-12-27"},
				  {"week": 10, "source_date": "2026-12-28", "source_end_date": "2025-01-12"}]}
				""");

		JsonNode weeks = WeeklyProgramYears.corrected(answer, START, END).path("weeks");

		assertThat(weeks.get(0).path("source_end_date").asString()).isEqualTo("2026-12-27");
		assertThat(weeks.get(1).path("source_end_date").asString())
				.as("12 January 2027 is in the program but more than a week past a week of 28 December")
				.isEqualTo("2025-01-12");
	}

	@Test
	void aWeekWhoseStartNoYearFitsKeepsItsOtherDatesAsTheyCame() {
		JsonNode answer = JSON.readTree("""
				{"weeks": [{"week": 8, "source_date": "2026-12-14"},
				  {"week": 9, "source_date": "2021-11-03", "source_end_date": "2021-11-09",
				   "sessions": [{"date": "2021-12-23", "title": "Workshop"}]},
				  {"week": 10, "source_date": "2026-12-28"}]}
				""");

		assertThat(WeeklyProgramYears.corrected(answer, START, END)).isEqualTo(answer);
	}

	@Test
	void aSpecialDayPrintedWithAnotherYearIsTakenAsAMisreadWhenItsDayFallsInTheProgram() {
		JsonNode answer = JSON.readTree(
				"""
						{"special_days": [{"date": "2025-12-12", "title": "Demo Day"}, {"date": "2025-06-12", "title": "Mezuniyet"}]}
						""");

		JsonNode days = WeeklyProgramYears.corrected(answer, START, END).path("special_days");

		assertThat(days.get(0).path("date").asString()).isEqualTo("2026-12-12");
		assertThat(days.get(1).path("date").asString()).isEqualTo("2025-06-12");
	}

	private static JsonNode weeks(String... starts) {
		StringBuilder rows = new StringBuilder();
		for (int index = 0; index < starts.length; index++) {
			rows.append(index == 0 ? "" : ",").append("{\"week\":").append(index + 1).append(",\"source_date\":\"")
					.append(starts[index]).append("\"}");
		}
		return JSON.readTree("{\"weeks\":[" + rows + "]}");
	}

	private static List<String> starts(JsonNode answer) {
		List<String> starts = new ArrayList<>();
		for (JsonNode week : answer.path("weeks")) {
			starts.add(week.path("source_date").asString());
		}
		return starts;
	}
}
