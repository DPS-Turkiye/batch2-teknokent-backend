package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;

public record RecommendMentorsRequest(
		@NotBlank String supportArea,
		@NotBlank String summary,
		String urgency,
		String mentorshipFormat,
		String meetingMode,
		String startupSector,
		String startupStage,
		String startupSummary,
		List<String> startupTechnologies,
		UUID excludedMentorId) {
}
