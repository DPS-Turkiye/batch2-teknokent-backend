package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** {@code week} is the week the screen showed; it must still be the running one. */
public record CheckInRequest(UUID actorId, @NotNull @Min(1) Integer week) {
}
