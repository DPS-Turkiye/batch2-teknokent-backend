package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * "Bu hafta" on a participant's Süreç: the program week running today, its planned content ({@code plan}, from the
 * weekly plan the coordinator saved; null when that week has none) and the syllabus phases that fall in it.
 * {@code week} is null before the start, after the end, or without a start date. {@code hasSyllabus} is false while
 * the coordinator has saved neither phases nor weeks, so the screen can tell "no syllabus" from "nothing this week".
 */
public record ProgramWeekResponse(
		UUID programId,
		String programName,
		LocalDate programStart,
		LocalDate programEnd,
		Integer week,
		Integer totalWeeks,
		LocalDate weekStart,
		LocalDate weekEnd,
		boolean notStarted,
		boolean ended,
		boolean hasSyllabus,
		ProgramWeekPlanResponse.Week plan,
		List<Phase> phases) {

	/** {@code current} marks the phase whose dates contain today. */
	public record Phase(
			UUID id,
			String name,
			int position,
			LocalDate startDate,
			LocalDate endDate,
			String expectedOutput,
			boolean current) {
	}
}
