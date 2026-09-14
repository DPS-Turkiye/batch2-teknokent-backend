package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record DocumentShareResponse(UUID mentorId, String mentorName, Instant sharedAt) {
}
