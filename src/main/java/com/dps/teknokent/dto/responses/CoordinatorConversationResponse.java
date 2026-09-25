package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.CoordinatorConversationCounterpart;

public record CoordinatorConversationResponse(
		UUID id,
		UUID programId,
		String programName,
		CoordinatorConversationCounterpart counterpartType,
		UUID counterpartId,
		String counterpartName,
		UUID coordinatorId,
		String coordinatorName,
		String lastMessagePreview,
		Instant lastMessageAt,
		boolean unread,
		long unreadCount,
		Instant otherSideReadAt) {
}
