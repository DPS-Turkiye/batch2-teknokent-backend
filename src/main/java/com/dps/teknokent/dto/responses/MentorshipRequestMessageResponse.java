package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipRequestMessageType;

public record MentorshipRequestMessageResponse(
		UUID id,
		UUID authorId,
		String authorName,
		MentorshipRequestMessageType messageType,
		String body,
		Instant createdAt) {
}
