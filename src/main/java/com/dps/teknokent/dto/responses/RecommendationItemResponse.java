package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

public record RecommendationItemResponse(
		UUID mentorId,
		int rank,
		boolean selected,
		String mentorName,
		String professionalTitle,
		String company,
		List<String> reasons,
		List<MentorEvidenceResponse> evidence) {
}
