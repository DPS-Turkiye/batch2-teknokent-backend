package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** A mentor's or a team member's new time for one of their meetings; the length defaults to the meeting's. */
public record RescheduleMeetingRequest(
		UUID actorId,
		@NotNull Instant scheduledAt,
		@Min(15) @Max(240) Integer durationMinutes) {
}
