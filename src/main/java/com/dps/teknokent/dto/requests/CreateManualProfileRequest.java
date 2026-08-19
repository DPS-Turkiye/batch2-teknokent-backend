package com.dps.teknokent.dto.requests;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateManualProfileRequest(
		@NotBlank String profileType,
		@NotNull JsonNode profile) {
}
