package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;

/**
 * Phases and a week-by-week plan proposed from a syllabus. Nothing is saved until the coordinator confirms them;
 * week dates come from the program dates sent with the request.
 */
public record SyllabusPhasesResponse(List<Phase> phases, List<ProgramWeekPlanResponse.Week> weeks, int pageCount) {

	public record Phase(String name, String expectedOutput, LocalDate startDate, LocalDate endDate) {
	}
}
