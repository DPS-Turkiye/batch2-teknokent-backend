package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code replacesMeetingId} turns the proposal into a reschedule of that meeting. */
public record ProposeMeetingRequest(
		UUID actorId,
		@NotNull Instant scheduledAt,
		@NotNull @Min(15) @Max(240) Integer durationMinutes,
		@Size(max = 500) String agenda,
		UUID replacesMeetingId) {
}
