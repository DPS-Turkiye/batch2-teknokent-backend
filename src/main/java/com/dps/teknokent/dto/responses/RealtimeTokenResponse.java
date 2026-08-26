package com.dps.teknokent.dto.responses;

import java.time.Instant;

public record RealtimeTokenResponse(
		String token,
		Instant expiresAt) {
}
