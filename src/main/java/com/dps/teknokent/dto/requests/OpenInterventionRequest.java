package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OpenInterventionRequest(
		UUID actorId,
		@NotNull UUID mentorshipId,
		@NotBlank @Size(max = 160) String action,
		@Size(max = 500) String note) {
}
