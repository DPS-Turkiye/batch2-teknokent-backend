package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** How many sessions the mentor plans for an accepted request; null clears the plan. */
public record UpdatePlannedSessionsRequest(
		@NotNull UUID mentorId,
		@Min(1) @Max(52) Integer plannedSessionCount) {
}
