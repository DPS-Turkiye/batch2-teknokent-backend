package com.dps.teknokent.dto.requests;

import java.time.Instant;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateMentorAvailabilitySlotRequest(
		@NotNull Instant startsAt,
		@Positive Integer durationMinutes) {
}
