package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record CoordinatorMessageResponse(
		UUID id,
		UUID senderId,
		String senderName,
		boolean mine,
		String body,
		Instant createdAt) {
}
