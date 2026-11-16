package com.dps.teknokent.util;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.entity.ProgramWeek;

/**
 * A program's weeks on the calendar: the dates its saved plan prints where it has them, the program calendar
 * (ProgramCalendar: week N starts 7(N-1) days after the program) everywhere else. Week numbers are the plan's own,
 * so the week found here is the one attendance is checked in for and the one "Bu hafta" shows.
 */
public final class ProgramSchedule {

	/** A week's first and last day. */
	public record Dates(LocalDate start, LocalDate end) {

		boolean contains(LocalDate day) {
			return !day.isBefore(start) && !day.isAfter(end);
		}
	}

	private final @Nullable LocalDate programStart;
	private final @Nullable LocalDate programEnd;
	private final TreeMap<Integer, Dates> printed;

	private ProgramSchedule(@Nullable LocalDate programStart, @Nullable LocalDate programEnd,
			Map<Integer, Dates> printed) {
		this.programStart = programStart;
		this.programEnd = programEnd;
		this.printed = new TreeMap<>(printed);
	}

	public static ProgramSchedule of(@Nullable LocalDate programStart, @Nullable LocalDate programEnd,
			List<ProgramWeek> weeks) {
		Map<Integer, Dates> printed = new TreeMap<>();
		for (ProgramWeek week : weeks) {
			if (week.getStartDate() != null && week.getEndDate() != null) {
				printed.put(week.getWeekNumber(), new Dates(week.getStartDate(), week.getEndDate()));
			}
		}
		return new ProgramSchedule(programStart, programEnd, printed);
	}

	/** The program calendar alone, for a program whose plan prints no dates. */
	public static ProgramSchedule computed(@Nullable LocalDate programStart, @Nullable LocalDate programEnd) {
		return new ProgramSchedule(programStart, programEnd, Map.of());
	}

	/** Whether week {@code week}'s dates are the plan's own rather than computed. */
	public boolean printed(int week) {
		return printed.containsKey(week);
	}

	/** Week {@code week}'s days: the printed ones, else the program calendar's; null without either. */
	public @Nullable Dates datesOf(int week) {
		Dates own = printed.get(week);
		if (own != null) {
			return own;
		}
		if (programStart == null) {
			return null;
		}
		return new Dates(ProgramCalendar.weekStart(programStart, week),
				ProgramCalendar.weekEnd(programStart, programEnd, week));
	}

	/**
	 * The week running on {@code today}: the saved week whose printed dates contain it (the later-starting one when
	 * two overlap), else the program calendar's week while the program runs. Null outside both.
	 */
	public @Nullable Integer weekOn(LocalDate today) {
		Integer found = null;
		LocalDate foundStart = null;
		for (Map.Entry<Integer, Dates> entry : printed.entrySet()) {
			Dates dates = entry.getValue();
			if (dates.contains(today) && (foundStart == null || !dates.start().isBefore(foundStart))) {
				found = entry.getKey();
				foundStart = dates.start();
			}
		}
		if (found != null) {
			return found;
		}
		if (programStart == null || today.isBefore(programStart)
				|| (programEnd != null && today.isAfter(programEnd))) {
			return null;
		}
		return ProgramCalendar.weekOn(programStart, programEnd, today);
	}

	/**
	 * The last of weeks 1–{@code totalWeeks} that has begun by {@code today}; every week once the program has ended,
	 * so an archived program keeps its weeks. 0 before any has begun.
	 */
	public int lastBegunWeek(int totalWeeks, LocalDate today) {
		if (programEnd != null && today.isAfter(programEnd)) {
			return totalWeeks;
		}
		int last = 0;
		for (int week = 1; week <= totalWeeks; week++) {
			Dates dates = datesOf(week);
			if (dates != null && !dates.start().isAfter(today)) {
				last = week;
			}
		}
		return last;
	}
}
