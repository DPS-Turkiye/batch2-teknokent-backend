package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest.SpecialDayInput;
import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest.WeekInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The reviewed weekly program, saved in one step: the weeks, the special days, the phases as week ranges (their
 * dates come from their weeks' dates) and the file they were read from. {@code seenPhaseCount} and
 * {@code seenWeekCount} are the
 * plan the coordinator was shown when the file was read; a plan that changed since is not overwritten. With
 * {@code keepPast}, a running program keeps its saved weeks before the current one, its past special days and its
 * completed phases; a past week, phase or special day the saved plan does not have still comes from the file.
 */
public record SaveWeeklyProgramRequest(
		UUID actorId,
		@NotNull UUID documentId,
		@NotNull @Size(min = 1, max = 104) List<@Valid @NotNull WeekInput> weeks,
		@NotNull @Size(max = 12) List<@Valid @NotNull PhaseInput> phases,
		@Size(max = ReplaceProgramWeeksRequest.MAX_SPECIAL_DAYS) List<@Valid @NotNull SpecialDayInput> specialDays,
		@Min(0) int seenPhaseCount,
		@Min(0) int seenWeekCount,
		boolean keepPast) {

	public SaveWeeklyProgramRequest(UUID actorId, UUID documentId, List<WeekInput> weeks, List<PhaseInput> phases,
			int seenPhaseCount, int seenWeekCount, boolean keepPast) {
		this(actorId, documentId, weeks, phases, null, seenPhaseCount, seenWeekCount, keepPast);
	}

	public record PhaseInput(
			@NotBlank @Size(max = 120) String name,
			@Size(max = 200) String expectedOutput,
			@Min(1) @Max(104) int firstWeek,
			@Min(1) @Max(104) int lastWeek) {
	}
}
