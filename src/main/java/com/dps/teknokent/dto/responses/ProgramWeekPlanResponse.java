package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;

/**
 * A program's weekly plan, as the coordinator edits it. Dates are null while the program has no start date.
 * {@code totalWeeks} is null without both program dates.
 */
public record ProgramWeekPlanResponse(Integer totalWeeks, List<Week> weeks) {

	public record Week(
			int week,
			LocalDate startDate,
			LocalDate endDate,
			String title,
			List<String> topics,
			List<String> activities,
			List<String> deliverables,
			boolean inferred) {
	}
}
