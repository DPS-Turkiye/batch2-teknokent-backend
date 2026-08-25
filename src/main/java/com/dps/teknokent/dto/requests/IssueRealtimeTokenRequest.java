package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record IssueRealtimeTokenRequest(
		@NotNull UUID userId,
		@NotNull UUID organizationId) {
}
