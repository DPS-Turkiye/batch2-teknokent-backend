package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RecommendationEvidenceSubmission(
		@NotBlank @Size(max = 80) String category,
		@NotBlank @Size(max = 1000) String claim,
		@NotBlank @Size(max = 1500) String quote,
		@Positive Integer sourcePage,
		@NotBlank @Size(max = 100) String profileVersion) {
}
