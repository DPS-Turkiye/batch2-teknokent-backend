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
		List<MentorEvidenceResponse> recommendationEvidence,
		Integer menteeCapacity,
		long activeMenteeCount,
		long completedMentorshipCount,
		List<String> programNames,
		List<MentorExperienceResponse> experiences) {

	// Recommendation ranking and its tests build profiles from the matching fields alone.
	public MentorProfileResponse(UUID id, String fullName, String email, String initials, String professionalTitle,
			String company, String availabilityLabel, String responseTimeLabel, String meetingMode,
			String communicationPreference, String mentoringStyle, String fitLabel, String experienceLevel,
			String sectorExperience, String startupStages, List<String> specialties,
			List<String> recommendationReasons, List<MentorEvidenceResponse> recommendationEvidence) {
		this(id, fullName, email, initials, professionalTitle, company, availabilityLabel, responseTimeLabel,
				meetingMode, communicationPreference, mentoringStyle, fitLabel, experienceLevel, sectorExperience,
				startupStages, specialties, recommendationReasons, recommendationEvidence, null, 0, 0, List.of(),
				List.of());
	}

	public static MentorProfileResponse fromEntity(MentorProfile profile) {
		return new MentorProfileResponse(
				profile.getId(), profile.getUser().getFullName(), profile.getUser().getEmail(), profile.getInitials(),
				profile.getProfessionalTitle(), profile.getCompany(), profile.getAvailabilityLabel(),
				profile.getResponseTimeLabel(), profile.getMeetingMode(), profile.getCommunicationPreference(),
				profile.getMentoringStyle(), profile.getFitLabel(), profile.getExperienceLevel(),
				profile.getSectorExperience(), profile.getStartupStages(), List.copyOf(profile.getSpecialties()),
				List.copyOf(profile.getRecommendationReasons()),
				profile.getConfirmedEvidence().stream().map(MentorEvidenceResponse::fromEntity).toList(),
				profile.getMenteeCapacity(), 0, 0, List.of(),
				profile.getExperiences().stream().map(MentorExperienceResponse::fromEntity).toList());
	}

	public MentorProfileResponse withActivity(long activeMentees, long completedMentorships, List<String> programs) {
		return new MentorProfileResponse(id, fullName, email, initials, professionalTitle, company,
				availabilityLabel, responseTimeLabel, meetingMode, communicationPreference, mentoringStyle, fitLabel,
				experienceLevel, sectorExperience, startupStages, specialties, recommendationReasons,
				recommendationEvidence, menteeCapacity, activeMentees, completedMentorships, List.copyOf(programs),
				experiences);
	}

	public MentorProfileResponse withRecommendation(List<String> reasons, List<MentorEvidenceResponse> evidence) {
		return new MentorProfileResponse(id, fullName, email, initials, professionalTitle, company,
				availabilityLabel, responseTimeLabel, meetingMode, communicationPreference, mentoringStyle, fitLabel,
				experienceLevel, sectorExperience, startupStages, specialties, reasons, evidence, menteeCapacity,
				activeMenteeCount, completedMentorshipCount, programNames, experiences);
	}
}
