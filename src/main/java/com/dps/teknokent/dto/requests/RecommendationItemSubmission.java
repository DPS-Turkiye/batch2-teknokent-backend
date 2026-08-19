package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

public record RecommendationItemSubmission(
		@NotNull UUID mentorId,
		@Min(1) @Max(20) int rank,
		boolean selected,
		@NotBlank @Size(max = 500) String mentorName,
		@NotBlank @Size(max = 500) String professionalTitle,
		@NotBlank @Size(max = 500) String company,
		@NotNull @Size(max = 5) List<@NotBlank @Size(max = 1000) String> reasons,
		@NotNull @Size(max = 3) List<@Valid RecommendationEvidenceSubmission> evidence) {
}
