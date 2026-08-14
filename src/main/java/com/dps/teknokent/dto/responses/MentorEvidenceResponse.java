package com.dps.teknokent.dto.responses;

public record MentorEvidenceResponse(
		String category,
		String claim,
		String quote,
		Integer sourcePage,
		String profileVersion) {
}
