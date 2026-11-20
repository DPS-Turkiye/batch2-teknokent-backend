package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** A finished move; {@code programId} is the person's own program afterwards. */
public record MemberTeamMoveResponse(
		UUID userId,
		TeamMoveTeamResponse from,
		TeamMoveTeamResponse to,
		@Nullable UUID programId,
		Instant movedAt) {
}
