package com.dps.teknokent.dto.responses;

public record AccountDecisionResponse(
		UserResponse user,
		String inviteCode) {
}
