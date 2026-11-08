package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Award the program's gate badge to {@code userId}; {@code actorId} comes from the proxy's session. */
public record AwardBadgeRequest(UUID actorId, @NotNull UUID userId) {
}
