package com.dps.teknokent.dto.responses;

import java.time.Instant;

/** A time a student can ask for, shaped like a mentor's availability slot. */
public record OfficeHoursSlotResponse(Instant startsAt, int durationMinutes) {
}
