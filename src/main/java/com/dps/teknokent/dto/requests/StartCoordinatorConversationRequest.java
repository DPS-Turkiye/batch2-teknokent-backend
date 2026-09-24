package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Exactly one of {@code organizationId} (a team) or {@code mentorId}. */
public record StartCoordinatorConversationRequest(
		UUID actorId,
		@NotNull UUID programId,
		UUID organizationId,
		UUID mentorId) {
}
