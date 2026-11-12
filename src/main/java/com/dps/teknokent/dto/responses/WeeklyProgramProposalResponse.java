package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;

import com.dps.teknokent.domain.WeeklyProgramWarning;

/**
 * A weekly program file read for review; nothing is saved. Week and phase dates come from the program calendar.
 * {@code notes} are lines of the file that belong to no week (a demo day after the last week, a general note);
 * {@code readingWarnings} are the AI's own remarks about the file. {@code existing} is what saving would replace.
 */
public record WeeklyProgramProposalResponse(
		int totalWeeks,
		List<ProgramWeekPlanResponse.Week> weeks,
		List<Phase> phases,
		List<Warning> warnings,
		List<String> notes,
		List<String> readingWarnings,
		int pageCount,
		Existing existing) {

	public record Phase(
			String name,
			String expectedOutput,
			int firstWeek,
			int lastWeek,
			LocalDate startDate,
			LocalDate endDate) {
	}

	/** {@code weeks} names the weeks the warning is about; empty when it is about the whole file. */
	public record Warning(WeeklyProgramWarning code, List<Integer> weeks) {
	}

	/**
	 * {@code currentWeek} is the first week a keep-past save replaces: the running week, or {@code totalWeeks + 1}
	 * once the program has ended. It is null until the program starts; once set, saving can keep the past.
	 */
	public record Existing(int phaseCount, int weekCount, Integer currentWeek) {
	}
}
