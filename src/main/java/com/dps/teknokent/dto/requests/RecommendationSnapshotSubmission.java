package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RecommendationSnapshotSubmission(
		@NotBlank @Size(max = 64) String matchingMethod,
		@Size(max = 255) String matchingModel,
		@NotNull Instant generatedAt,
		@Size(max = 500) String startupName,
		@Size(max = 500) String startupSector,
		@Size(max = 500) String startupStage,
		@Size(max = 5000) String startupSummary,
		@NotNull @Size(max = 50) List<@NotBlank @Size(max = 255) String> startupTechnologies,
		@NotNull @Size(min = 1, max = 10) List<@Valid RecommendationItemSubmission> recommendations) {
}
