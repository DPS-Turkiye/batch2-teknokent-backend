package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;

class ProgramScheduleTest {

	private static final LocalDate START = LocalDate.of(2026, 10, 26);

	@Test
	void aSessionBeyondTheWeekRangeKeepsItsWeekNumberAndIsNotPastOnItsDate() {
		LocalDate monday = LocalDate.of(2026, 12, 28);
		LocalDate sessionDay = LocalDate.of(2027, 1, 4);
		ProgramWeek week = datedWeek(10, monday, monday.plusDays(6));
		week.getSessions().add(new ProgramWeekSession(sessionDay, null, null, null, "Pitch"));
		ProgramSchedule schedule = ProgramSchedule.of(LocalDate.of(2026, 10, 26), LocalDate.of(2027, 1, 9),
				List.of(week));
		assertThat(schedule.weekOn(sessionDay)).isEqualTo(10);
		assertThat(schedule.datesOf(10).end()).isEqualTo(monday.plusDays(6));
		assertThat(schedule.isPast(10, sessionDay)).isFalse();
		assertThat(schedule.isPast(10, sessionDay.plusDays(1))).isTrue();
	}

	@Test
	void printedWeekWinsEvenOutsideTheConfiguredProgramDates() {
		ProgramWeek week = datedWeek(3, START.minusDays(7), START.minusDays(3));
		ProgramSchedule schedule = ProgramSchedule.of(START, START.plusDays(20), List.of(week));
		assertThat(schedule.weekOn(START.minusDays(5))).isEqualTo(3);
		assertThat(schedule.datesOf(3)).isEqualTo(new ProgramSchedule.Dates(START.minusDays(7), START.minusDays(3)));
		assertThat(schedule.printed(3)).isTrue();
	}

	@Test
	void undatedWeeksUseTheProgramCalendarAndOutsideDatesReturnNoWeek() {
		ProgramSchedule schedule = ProgramSchedule.computed(START, START.plusDays(20));
		assertThat(schedule.weekOn(START.plusDays(8))).isEqualTo(2);
		assertThat(schedule.weekOn(START.minusDays(1))).isNull();
		assertThat(schedule.weekOn(START.plusDays(21))).isNull();
		assertThat(schedule.datesOf(2)).isEqualTo(new ProgramSchedule.Dates(START.plusDays(7), START.plusDays(13)));
		assertThat(schedule.printed(2)).isFalse();
	}

	@Test
	void laterStartingPrintedWeekWinsAnOverlap() {
		ProgramSchedule schedule = ProgramSchedule.of(START, START.plusDays(20), List.of(
				datedWeek(1, START, START.plusDays(8)), datedWeek(2, START.plusDays(7), START.plusDays(13))));
		assertThat(schedule.weekOn(START.plusDays(8))).isEqualTo(2);
	}

	@Test
	void aPlanWithoutAnyDatesHasNoCalendarWeek() {
		ProgramSchedule schedule = ProgramSchedule.computed(null, null);
		assertThat(schedule.weekOn(START)).isNull();
		assertThat(schedule.datesOf(1)).isNull();
		assertThat(schedule.lastBegunWeek(3, START)).isZero();
	}

	@Test
	void noWeekRunsBeforeTheSyllabusStartsOrOnADayBetweenTwoPrintedWeeks() {
		// The program calendar starts on 26 Oct; the syllabus prints week 1 from 2 Nov and week 2 from 10 Nov.
		ProgramSchedule schedule = ProgramSchedule.of(START, START.plusDays(60), List.of(
				datedWeek(1, START.plusDays(7), START.plusDays(13)),
				datedWeek(2, START.plusDays(15), START.plusDays(21))));

		assertThat(schedule.weekOn(START.plusDays(2))).as("before the printed week 1").isNull();
		assertThat(schedule.weekOn(START.plusDays(14))).as("the day between weeks 1 and 2").isNull();
		assertThat(schedule.weekOn(START.plusDays(10))).isEqualTo(1);
		assertThat(schedule.weekOn(START.plusDays(29))).as("past the printed weeks, inside the program").isNull();
	}

	@Test
	void theLastBegunWeekFollowsTheDatesAndKeepsEveryWeekOnceTheProgramHasEnded() {
		LocalDate end = START.plusDays(27);
		ProgramSchedule computed = ProgramSchedule.computed(START, end);
		assertThat(computed.lastBegunWeek(4, START.minusDays(1))).isZero();
		assertThat(computed.lastBegunWeek(4, START)).isEqualTo(1);
		assertThat(computed.lastBegunWeek(4, START.plusDays(8))).isEqualTo(2);
		assertThat(computed.lastBegunWeek(4, end.plusDays(30))).isEqualTo(4);
		ProgramSchedule printed = ProgramSchedule.of(START, end,
				List.of(datedWeek(2, START.plusDays(10), START.plusDays(16))));
		assertThat(printed.lastBegunWeek(4, START.plusDays(8))).as("week 2 prints a later start").isEqualTo(1);
	}

	private static ProgramWeek datedWeek(int number, LocalDate start, LocalDate end) {
		ProgramWeek week = new ProgramWeek();
		week.setWeekNumber(number);
		week.setStartDate(start);
		week.setEndDate(end);
		return week;
	}
}
