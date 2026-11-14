package com.dps.teknokent.dto.responses;

import org.jspecify.annotations.Nullable;

public record AccountDecisionResponse(
		UserResponse user,
		@Nullable String inviteCode) {
}
