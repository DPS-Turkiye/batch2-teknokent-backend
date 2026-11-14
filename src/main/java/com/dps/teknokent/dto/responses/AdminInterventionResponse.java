package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record AdminInterventionResponse(
		UUID id,
		UUID mentorshipId,
		UUID coordinatorId,
		String coordinatorName,
		String openedByName,
		String action,
		String note,
		Instant openedAt,
		@Nullable Instant readAt,
		@Nullable Instant plannedMeetingAt,
		Instant resolvedAt) {
}
