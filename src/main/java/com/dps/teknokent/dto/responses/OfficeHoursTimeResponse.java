package com.dps.teknokent.dto.responses;

import java.time.Instant;

/** A meeting time with its end, as the screens show it. */
public record OfficeHoursTimeResponse(Instant startsAt, Instant endsAt) {
}
