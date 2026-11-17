package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.AttendanceState;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramWeek;

class ProgramAttendanceWindowTest {

	private static final LocalDate START = LocalDate.of(2026, 11, 2);
	private static final LocalDate END = LocalDate.of(2026, 11, 26);

	@Test
	void storedDateWeekKeepsItsAttendanceNumberOutsideTheProgramCalendar() {
		ProgramWeek week = new ProgramWeek();
		week.setWeekNumber(3);
		week.setStartDate(START.minusDays(7));
		week.setEndDate(START.minusDays(1));
		var window = ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, START, END), START.minusDays(3),
				List.of(week));
		assertThat(window.state()).isEqualTo(AttendanceState.OPEN);
		assertThat(window.week()).isEqualTo(3);
		assertThat(window.weekStart()).isEqualTo(week.getStartDate());
		assertThat(window.weekEnd()).isEqualTo(week.getEndDate());
	}

	@Test
	void opensTheRunningWeekWithItsDays() {
		var window = ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, START, END),
				LocalDate.of(2026, 11, 12));

		assertThat(window.state()).isEqualTo(AttendanceState.OPEN);
		assertThat(window.week()).isEqualTo(2);
		assertThat(window.totalWeeks()).isEqualTo(4);
		assertThat(window.weekStart()).isEqualTo(LocalDate.of(2026, 11, 9));
		assertThat(window.weekEnd()).isEqualTo(LocalDate.of(2026, 11, 15));
	}

	@Test
	void theLastWeekEndsWithTheProgram() {
		var window = ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, START, END), END);

		assertThat(window.week()).isEqualTo(4);
		assertThat(window.weekStart()).isEqualTo(LocalDate.of(2026, 11, 23));
		assertThat(window.weekEnd()).isEqualTo(END);
	}

	@Test
	void theFirstAndLastDaysAreInside() {
		assertThat(ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, START, END), START).week())
				.isEqualTo(1);
		assertThat(ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, START, END), END).state())
				.isEqualTo(AttendanceState.OPEN);
	}

	@Test
	void nothingIsOpenBeforeTheStartOrAfterTheEnd() {
		Program program = program(ProgramStatus.ACTIVE, START, END);

		assertThat(ProgramAttendanceService.window(program, START.minusDays(1)).state())
				.isEqualTo(AttendanceState.NOT_STARTED);
		assertThat(ProgramAttendanceService.window(program, END.plusDays(1)).state()).isEqualTo(AttendanceState.ENDED);
	}

	@Test
	void aProgramWithoutAStartDateOrNotRunningHasNoWeeks() {
		assertThat(ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, null, END), START).state())
				.isEqualTo(AttendanceState.UNSCHEDULED);
		assertThat(ProgramAttendanceService.window(program(ProgramStatus.DRAFT, START, END), START).state())
				.isEqualTo(AttendanceState.UNSCHEDULED);
		assertThat(ProgramAttendanceService.window(program(ProgramStatus.COMPLETED, START, END), START).state())
				.isEqualTo(AttendanceState.UNSCHEDULED);
	}

	@Test
	void anOpenEndedProgramKeepsCountingWeeks() {
		var window = ProgramAttendanceService.window(program(ProgramStatus.ACTIVE, START, null), START.plusDays(70));

		assertThat(window.week()).isEqualTo(11);
		assertThat(window.totalWeeks()).isNull();
		assertThat(window.weekEnd()).isEqualTo(START.plusDays(76));
	}

	private static Program program(ProgramStatus status, LocalDate start, LocalDate end) {
		Program program = new Program();
		program.setStatus(status);
		program.setStartDate(start);
		program.setEndDate(end);
		return program;
	}
}
