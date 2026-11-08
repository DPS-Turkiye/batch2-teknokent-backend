package com.dps.teknokent.util;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.dps.teknokent.domain.ProgramLifecycle;
import com.dps.teknokent.domain.ProgramStatus;
import com.dps.teknokent.entity.ProgramPhase;

/** Date arithmetic for a program's schedule, with "today" passed in so it can be tested. */
public final class ProgramCalendar {

	private ProgramCalendar() {
	}

	public static ProgramLifecycle lifecycle(ProgramStatus status, LocalDate startDate, LocalDate today) {
		if (status == ProgramStatus.DRAFT) {
			return ProgramLifecycle.DRAFT;
		}
		if (status == ProgramStatus.COMPLETED) {
			return ProgramLifecycle.COMPLETED;
		}
		return startDate != null && startDate.isAfter(today) ? ProgramLifecycle.UPCOMING : ProgramLifecycle.ACTIVE;
	}

	/** Whole weeks from start to end, counting a partial last week; null without both dates. */
	public static Integer totalWeeks(LocalDate startDate, LocalDate endDate) {
		if (startDate == null || endDate == null) {
			return null;
		}
		long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
		return (int) Math.max(1, (days + 6) / 7);
	}

	/** The running week, 1-based and capped at the total; null before the start or without a start date. */
	public static Integer currentWeek(LocalDate startDate, LocalDate endDate, LocalDate today) {
		if (startDate == null || today.isBefore(startDate)) {
			return null;
		}
		int week = (int) (ChronoUnit.DAYS.between(startDate, today) / 7) + 1;
		Integer total = totalWeeks(startDate, endDate);
		return total == null ? week : Math.min(week, total);
	}

	/**
	 * The phase running today: the one whose dates contain today, else the latest one that has started.
	 * Phases without dates never count as current.
	 */
	public static ProgramPhase currentPhase(List<ProgramPhase> phases, LocalDate today) {
		ProgramPhase latestStarted = null;
		for (ProgramPhase phase : phases) {
			LocalDate start = phase.getStartDate();
			if (start == null || start.isAfter(today)) {
				continue;
			}
			LocalDate end = phase.getEndDate();
			if (end == null || !end.isBefore(today)) {
				return phase;
			}
			latestStarted = phase;
		}
		return latestStarted;
	}
}
