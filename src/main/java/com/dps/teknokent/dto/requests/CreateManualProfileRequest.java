package com.dps.teknokent.dto.requests;

import com.dps.teknokent.domain.ProfileType;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

public record CreateManualProfileRequest(
		@NotNull ProfileType profileType,
		@NotNull JsonNode profile) {
}
