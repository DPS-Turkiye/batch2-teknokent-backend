package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import com.dps.teknokent.entity.MentorshipRequestRecommendationItem;
import com.dps.teknokent.entity.MentorshipRequestRecommendationRun;

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

	public static RecommendationSnapshotResponse fromEntity(MentorshipRequestRecommendationRun run) {
		return new RecommendationSnapshotResponse(
				run.getMatchingMethod(),
				run.getMatchingModel(),
				run.getGeneratedAt(),
				run.getStartupName(),
				run.getStartupSector(),
				run.getStartupStage(),
				run.getStartupSummary(),
				List.copyOf(run.getStartupTechnologies()),
				run.getRecommendations().stream()
						.sorted(Comparator.comparingInt(MentorshipRequestRecommendationItem::getRank))
						.map(RecommendationItemResponse::fromEntity)
						.toList());
	}
}
