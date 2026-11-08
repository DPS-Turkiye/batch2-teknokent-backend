package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursMessageType;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;

public record OfficeHoursRequestResponse(
		UUID id,
		UUID courseId,
		OfficeHoursRequestStatus status,
		OfficeHoursPersonResponse student,
		OfficeHoursPersonResponse instructor,
		String topic,
		String expectedOutcome,
		List<String> preferredSlots,
		List<String> proposedSlots,
		String instructorMessage,
		String rejectionReason,
		List<Message> messages,
		OfficeHoursSlotResponse meeting,
		Cancellation cancellation,
		Instant createdAt) {

	/** Who called the accepted meeting off, when, and why if they said. */
	public record Cancellation(UUID cancelledById, String reason, Instant cancelledAt) {
	}

	public record Message(UUID id, UUID authorId, OfficeHoursMessageType type, String body, Instant createdAt) {
	}
}
