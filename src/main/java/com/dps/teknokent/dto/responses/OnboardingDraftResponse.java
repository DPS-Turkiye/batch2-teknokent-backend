package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.Map;

public record OnboardingDraftResponse(
		String kind,
		String step,
		Map<String, Object> payload,
		Instant updatedAt) {
}
