package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingStatus;

public record MeetingResponse(
		UUID id,
		UUID mentorshipRequestId,
		UUID organizationId,
		String organizationName,
		UUID mentorId,
		String mentorName,
		Instant scheduledAt,
		Integer durationMinutes,
		String agenda,
		String notes,
		String outcomes,
		String meetLink,
		MeetingStatus status) {
}
