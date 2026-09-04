package com.dps.teknokent.dto.requests;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpsertOnboardingDraftRequest(
		@NotBlank @Size(max = 64) String kind,
		@NotBlank @Size(max = 64) String step,
		@NotNull Map<String, Object> payload) {
}
