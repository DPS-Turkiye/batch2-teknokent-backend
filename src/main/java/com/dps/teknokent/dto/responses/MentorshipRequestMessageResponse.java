package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipRequestMessageType;
import com.dps.teknokent.entity.MentorshipRequestMessage;

public record MentorshipRequestMessageResponse(
		UUID id,
		UUID authorId,
		String authorName,
		MentorshipRequestMessageType messageType,
		String body,
		Instant createdAt) {

	public static MentorshipRequestMessageResponse fromEntity(MentorshipRequestMessage message) {
		return new MentorshipRequestMessageResponse(
				message.getId(),
				message.getAuthor().getId(),
				message.getAuthor().getFullName(),
				message.getMessageType(),
				message.getBody(),
				message.getCreatedAt());
	}
}
