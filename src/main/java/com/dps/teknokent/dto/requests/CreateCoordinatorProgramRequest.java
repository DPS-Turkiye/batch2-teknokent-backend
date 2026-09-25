package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The "Yeni program" wizard in one request: basic info, rules and the teams with their leads. */
public record CreateCoordinatorProgramRequest(
		UUID actorId,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 2000) String description,
		LocalDate startDate,
		LocalDate endDate,
		@NotNull @Size(max = 10) List<UUID> coordinatorIds,
		@NotNull @Min(1) @Max(20) Integer defaultMentorCapacity,
		@NotNull @Min(1) @Max(90) Integer meetingFrequencyDays,
		@NotNull @Min(1) @Max(180) Integer communicationGapDays,
		@NotNull @Min(1) @Max(365) Integer bottleneckFlagDays,
		@NotNull @Size(max = 200) List<@Valid TeamInviteInput> teams) {
}
