package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record MentorAvailabilitySlotResponse(UUID id, Instant startsAt, int durationMinutes) {
}
