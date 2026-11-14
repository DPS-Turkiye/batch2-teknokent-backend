package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A program's weekly plan, as the coordinator edits it. Dates are null while the program has no start date.
 * {@code totalWeeks} is null without both program dates.
 */
public record ProgramWeekPlanResponse(@Nullable Integer totalWeeks, List<Week> weeks) {

	public record Week(
			int week,
			@Nullable LocalDate startDate,
			@Nullable LocalDate endDate,
			String title,
			List<String> topics,
			List<String> activities,
			List<String> deliverables,
			boolean inferred) {
	}
}
