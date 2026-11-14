package com.dps.teknokent.dto.responses;

import com.dps.teknokent.entity.MentorProfileEvidence;
import com.dps.teknokent.entity.RecommendationEvidenceSnapshot;

public record MentorEvidenceResponse(
		String category,
		String claim,
		String quote,
		Integer sourcePage,
		String profileVersion) {

	public static MentorEvidenceResponse fromEntity(RecommendationEvidenceSnapshot evidence) {
		return new MentorEvidenceResponse(
				evidence.getCategory(),
				evidence.getClaim(),
				evidence.getQuote(),
				evidence.getSourcePage(),
				evidence.getProfileVersion());
	}

	public static MentorEvidenceResponse fromEntity(MentorProfileEvidence evidence) {
		return new MentorEvidenceResponse(
				evidence.getCategory(), evidence.getClaim(), evidence.getQuote(),
				evidence.getSourcePage(), evidence.getProfileVersion());
	}
}
