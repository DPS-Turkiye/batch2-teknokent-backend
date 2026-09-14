package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ShareDocumentRequest(
		@NotNull UUID actorId,
		@NotNull UUID mentorId) {
}
