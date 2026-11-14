package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/** The profile as the user reviewed it; it must be a JSON object, which the service checks. */
public record ConfirmProfileAnalysisRequest(@NotNull JsonNode profile) {
}
