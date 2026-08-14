package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;

public record RecommendationSnapshotResponse(
		String matchingMethod,
		String matchingModel,
		Instant generatedAt,
		String startupName,
		String startupSector,
		String startupStage,
		String startupSummary,
		List<String> startupTechnologies,
		List<RecommendationItemResponse> recommendations) {
}
