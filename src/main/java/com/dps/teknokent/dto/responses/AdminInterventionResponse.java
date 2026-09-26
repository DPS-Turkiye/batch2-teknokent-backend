package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record AdminInterventionResponse(
		UUID id,
		UUID mentorshipId,
		UUID coordinatorId,
		String coordinatorName,
		String openedByName,
		String action,
		String note,
		Instant openedAt,
		Instant readAt,
		Instant plannedMeetingAt,
		Instant resolvedAt) {
}
