package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.MentorMatchingMethod;

public record MentorRecommendationResult(
		MentorMatchingMethod matchingMethod,
		@Nullable String matchingModel,
		Instant generatedAt,
		List<MentorProfileResponse> mentors) {
}
