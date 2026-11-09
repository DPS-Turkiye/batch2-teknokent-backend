package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** {@code signedInAt}: when the session asking for the token began, so an admin's later "end all sign-ins" closes it. */
public record IssueRealtimeTokenRequest(
		@NotNull UUID userId,
		@NotNull UUID organizationId,
		Instant signedInAt) {
}
