package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

public record CoordinatorMessageResponse(
		UUID id,
		UUID senderId,
		@Nullable String senderName,
		boolean mine,
		String body,
		Instant createdAt) {
}
