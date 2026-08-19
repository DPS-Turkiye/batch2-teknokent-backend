package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.MentorshipRequestRecommendationItem;

public record RecommendationItemResponse(
		UUID mentorId,
		int rank,
		boolean selected,
		String mentorName,
		String professionalTitle,
		String company,
		List<String> reasons,
		List<MentorEvidenceResponse> evidence) {

	public static RecommendationItemResponse fromEntity(MentorshipRequestRecommendationItem item) {
		return new RecommendationItemResponse(
				item.getMentor().getId(),
				item.getRank(),
				item.isSelected(),
				item.getMentorName(),
				item.getProfessionalTitle(),
				item.getCompany(),
				List.copyOf(item.getReasons()),
				item.getEvidence().stream().map(MentorEvidenceResponse::fromEntity).toList());
	}
}
