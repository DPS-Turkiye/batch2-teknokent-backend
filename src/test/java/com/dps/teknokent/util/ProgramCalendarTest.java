package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.entity.ProgramPhase;

class ProgramCalendarTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 9, 24);

	@Test
	void completedWinsOverDates() {
		assertThat(ProgramCalendar.lifecycle(ProgramStatus.COMPLETED, TODAY.plusDays(10), TODAY))
				.isEqualTo(ProgramLifecycle.COMPLETED);
	}

	@Test
	void aDraftStaysADraftWhateverItsDates() {
		assertThat(ProgramCalendar.lifecycle(ProgramStatus.DRAFT, TODAY.minusDays(10), TODAY))
				.isEqualTo(ProgramLifecycle.DRAFT);
		assertThat(ProgramCalendar.lifecycle(ProgramStatus.DRAFT, null, TODAY)).isEqualTo(ProgramLifecycle.DRAFT);
	}

	@Test
	void anActiveProgramThatHasNotStartedIsUpcoming() {
		assertThat(ProgramCalendar.lifecycle(ProgramStatus.ACTIVE, TODAY.plusDays(1), TODAY))
				.isEqualTo(ProgramLifecycle.UPCOMING);
		assertThat(ProgramCalendar.lifecycle(ProgramStatus.ACTIVE, TODAY, TODAY)).isEqualTo(ProgramLifecycle.ACTIVE);
		assertThat(ProgramCalendar.lifecycle(ProgramStatus.ACTIVE, null, TODAY)).isEqualTo(ProgramLifecycle.ACTIVE);
	}

	@Test
	void countsWeeksIncludingAPartialLastWeek() {
		assertThat(ProgramCalendar.totalWeeks(TODAY, TODAY.plusDays(111))).isEqualTo(16);
		assertThat(ProgramCalendar.totalWeeks(TODAY, TODAY.plusDays(112))).isEqualTo(17);
		assertThat(ProgramCalendar.totalWeeks(TODAY, TODAY)).isEqualTo(1);
		assertThat(ProgramCalendar.totalWeeks(null, TODAY)).isNull();
	}

	@Test
	void theCurrentWeekIsOneBasedAndCapped() {
		LocalDate start = TODAY.minusDays(35);
		assertThat(ProgramCalendar.currentWeek(start, start.plusDays(111), TODAY)).isEqualTo(6);
		assertThat(ProgramCalendar.currentWeek(TODAY, null, TODAY)).isEqualTo(1);
		assertThat(ProgramCalendar.currentWeek(TODAY.plusDays(1), null, TODAY)).isNull();
		assertThat(ProgramCalendar.currentWeek(start, start.plusDays(6), TODAY)).isEqualTo(1);
	}

	@Test
	void theCurrentPhaseContainsTodayOrIsTheLatestStarted() {
		ProgramPhase applications = phase("Başvuru", TODAY.minusDays(60), TODAY.minusDays(31));
		ProgramPhase mentoring = phase("Mentorluk dönemi", TODAY.minusDays(30), TODAY.plusDays(30));
		ProgramPhase demoDay = phase("Demo Day", TODAY.plusDays(31), TODAY.plusDays(31));
		assertThat(ProgramCalendar.currentPhase(List.of(applications, mentoring, demoDay), TODAY)).isSameAs(mentoring);

		ProgramPhase gapBefore = phase("Seçim", TODAY.minusDays(20), TODAY.minusDays(10));
		assertThat(ProgramCalendar.currentPhase(List.of(applications, gapBefore, demoDay), TODAY)).isSameAs(gapBefore);
	}

	@Test
	void undatedAndFuturePhasesAreNeverCurrent() {
		assertThat(ProgramCalendar.currentPhase(List.of(phase("Taslak", null, null)), TODAY)).isNull();
		assertThat(ProgramCalendar.currentPhase(List.of(phase("Demo Day", TODAY.plusDays(1), null)), TODAY)).isNull();
	}

	private static ProgramPhase phase(String name, LocalDate start, LocalDate end) {
		ProgramPhase phase = new ProgramPhase();
		phase.setName(name);
		phase.setStartDate(start);
		phase.setEndDate(end);
		return phase;
	}
}
