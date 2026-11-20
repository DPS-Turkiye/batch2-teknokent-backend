package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A finished move; {@code programId} is the person's own program afterwards. When a solo founder moved, the old
 * startup was archived and {@code withdrawnRequests} of its unanswered requests were closed.
 */
public record MemberTeamMoveResponse(
		UUID userId,
		TeamMoveTeamResponse from,
		TeamMoveTeamResponse to,
		@Nullable UUID programId,
		boolean archivedFrom,
		int withdrawnRequests,
		Instant movedAt) {
}
