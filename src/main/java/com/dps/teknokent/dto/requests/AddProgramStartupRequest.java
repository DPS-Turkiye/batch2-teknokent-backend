package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AddProgramStartupRequest(
		@NotNull UUID organizationId,
		UUID actorId) {
}
