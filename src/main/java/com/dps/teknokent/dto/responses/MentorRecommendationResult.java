package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;

public record MentorRecommendationResult(
		String matchingMethod,
		String matchingModel,
		Instant generatedAt,
		List<MentorProfileResponse> mentors) {
}
