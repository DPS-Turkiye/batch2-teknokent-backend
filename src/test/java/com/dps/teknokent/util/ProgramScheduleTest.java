package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.entity.ProgramWeek;

class ProgramScheduleTest {

	private static final LocalDate START = LocalDate.of(2026, 10, 26);

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

	private static ProgramWeek datedWeek(int number, LocalDate start, LocalDate end) {
		ProgramWeek week = new ProgramWeek();
		week.setWeekNumber(number);
		week.setStartDate(start);
		week.setEndDate(end);
		return week;
	}
}
