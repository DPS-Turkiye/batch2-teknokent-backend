package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest.WeekInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The reviewed weekly program, saved in one step: the weeks, the phases as week ranges (their dates come from the
 * program calendar) and the file they were read from. {@code seenPhaseCount} and {@code seenWeekCount} are the
 * plan the coordinator was shown when the file was read; a plan that changed since is not overwritten. With
 * {@code keepPast}, a running program keeps its saved weeks before the current one and its completed phases.
 */
public record SaveWeeklyProgramRequest(
		UUID actorId,
		@NotNull UUID documentId,
		@NotNull @Size(min = 1, max = 104) List<@Valid @NotNull WeekInput> weeks,
		@NotNull @Size(max = 12) List<@Valid @NotNull PhaseInput> phases,
		@Min(0) int seenPhaseCount,
		@Min(0) int seenWeekCount,
		boolean keepPast) {

	public record PhaseInput(
			@NotBlank @Size(max = 120) String name,
			@Size(max = 200) String expectedOutput,
			@Min(1) @Max(104) int firstWeek,
			@Min(1) @Max(104) int lastWeek) {
	}
}
