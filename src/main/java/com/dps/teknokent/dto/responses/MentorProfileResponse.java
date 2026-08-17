package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.MentorProfile;

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

	public static MentorProfileResponse fromEntity(MentorProfile profile) {
		return new MentorProfileResponse(
				profile.getId(), profile.getUser().getFullName(), profile.getUser().getEmail(), profile.getInitials(),
				profile.getProfessionalTitle(), profile.getCompany(), profile.getAvailabilityLabel(),
				profile.getResponseTimeLabel(), profile.getMeetingMode(), profile.getCommunicationPreference(),
				profile.getMentoringStyle(), profile.getFitLabel(), profile.getExperienceLevel(),
				profile.getSectorExperience(), profile.getStartupStages(), List.copyOf(profile.getSpecialties()),
				List.copyOf(profile.getRecommendationReasons()),
				profile.getConfirmedEvidence().stream().map(MentorEvidenceResponse::fromEntity).toList());
	}
}
