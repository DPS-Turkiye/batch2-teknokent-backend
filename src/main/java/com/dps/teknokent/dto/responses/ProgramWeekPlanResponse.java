package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.WeekVisibility;

/**
 * A program's weekly plan, as the coordinator edits it. A week's dates are the ones its syllabus prints
 * ({@code datesFromSource}), else the program calendar's; null while the program has no start date and the week
 * prints none. {@code totalWeeks} is null without both program dates. {@code dateCheck} is set when the plan's
 * printed dates start or end on another day than the program does.
 */
public record ProgramWeekPlanResponse(
		@Nullable Integer totalWeeks,
		List<Week> weeks,
		List<SpecialDay> specialDays,
		@Nullable DateCheck dateCheck) {

	public ProgramWeekPlanResponse(@Nullable Integer totalWeeks, List<Week> weeks) {
		this(totalWeeks, weeks, List.of(), null);
	}

	/**
	 * {@code menteeVisibility} is the coordinator's setting for the week, sent only in the coordinator's plan; null in
	 * a read proposal and in what participants get.
	 */
	public record Week(
			int week,
			@Nullable LocalDate startDate,
			@Nullable LocalDate endDate,
			boolean datesFromSource,
			String title,
			List<String> topics,
			List<String> activities,
			List<String> deliverables,
			boolean inferred,
			List<Session> sessions,
			@Nullable WeekVisibility menteeVisibility) {
		public Week(int week, @Nullable LocalDate startDate, @Nullable LocalDate endDate, boolean datesFromSource,
				String title, List<String> topics, List<String> activities, List<String> deliverables,
				boolean inferred, List<Session> sessions) {
			this(week, startDate, endDate, datesFromSource, title, topics, activities, deliverables, inferred,
					sessions, null);
		}

		public Week withVisibility(@Nullable WeekVisibility visibility) {
			return new Week(week, startDate, endDate, datesFromSource, title, topics, activities, deliverables,
					inferred, sessions, visibility);
		}

		public Week(int week, @Nullable LocalDate startDate, @Nullable LocalDate endDate, String title,
				List<String> topics, List<String> activities, List<String> deliverables, boolean inferred) {
			this(week, startDate, endDate, false, title, topics, activities, deliverables, inferred, List.of());
		}
	}

	/** A session of a week on its own day, as the syllabus prints it. */
	public record Session(
			LocalDate date,
			@Nullable LocalTime startTime,
			@Nullable LocalTime endTime,
			@Nullable String place,
			@Nullable String title) {
	}

	/** A day that is an event of its own (a bootcamp, a demo day), not part of a week. */
	public record SpecialDay(
			LocalDate date,
			@Nullable LocalTime startTime,
			@Nullable LocalTime endTime,
			@Nullable String place,
			String title,
			List<String> details) {
	}

	/** The first and last day the plan prints, which differ from the program's start or end. */
	public record DateCheck(LocalDate syllabusStart, LocalDate syllabusEnd) {
	}
}
