package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

public record MentorProfileResponse(
		UUID id,
		String fullName,
		String email,
		String initials,
		String professionalTitle,
		String company,
		String availabilityLabel,
		String responseTimeLabel,
		String meetingMode,
		String communicationPreference,
		String mentoringStyle,
		String fitLabel,
		String experienceLevel,
		String sectorExperience,
		String startupStages,
		List<String> specialties,
		List<String> recommendationReasons,
		List<MentorEvidenceResponse> recommendationEvidence) {
}
