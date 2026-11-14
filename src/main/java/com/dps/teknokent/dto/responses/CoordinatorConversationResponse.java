package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.CoordinatorConversationCounterpart;

public record CoordinatorConversationResponse(
		UUID id,
		UUID programId,
		@Nullable String programName,
		CoordinatorConversationCounterpart counterpartType,
		UUID counterpartId,
		String counterpartName,
		@Nullable UUID coordinatorId,
		@Nullable String coordinatorName,
		String lastMessagePreview,
		Instant lastMessageAt,
		boolean unread,
		long unreadCount,
		Instant otherSideReadAt) {
}
