package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingFrequency;
import com.dps.teknokent.domain.MentorLanguage;
import com.dps.teknokent.domain.PrepFileRequirement;
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
		String about,
		int defaultMeetingDurationMinutes,
		int meetingBufferMinutes,
		MeetingFrequency meetingFrequency,
		PrepFileRequirement prepFileRequirement,
		List<MentorLanguage> languages,
		long activeMenteeCount,
		long completedMentorshipCount,
		List<String> programNames,
		List<MentorProgramResponse> programs,
		List<MentorExperienceResponse> experiences,
		List<String> offerings,
		StudentProfileResponse studentProfile) {

	/** Without a student profile, for callers that build a response from parts. */
	public MentorProfileResponse(UUID id, String fullName, String email, String initials, String professionalTitle,
			String company, String availabilityLabel, String responseTimeLabel, String meetingMode,
			String communicationPreference, String mentoringStyle, String fitLabel, String experienceLevel,
			String sectorExperience, String startupStages, List<String> specialties,
			List<String> recommendationReasons, List<MentorEvidenceResponse> recommendationEvidence,
			Integer menteeCapacity, String about, int defaultMeetingDurationMinutes, int meetingBufferMinutes,
			MeetingFrequency meetingFrequency, PrepFileRequirement prepFileRequirement, List<MentorLanguage> languages,
			long activeMenteeCount, long completedMentorshipCount, List<String> programNames,
			List<MentorProgramResponse> programs, List<MentorExperienceResponse> experiences, List<String> offerings) {
		this(id, fullName, email, initials, professionalTitle, company, availabilityLabel, responseTimeLabel,
				meetingMode, communicationPreference, mentoringStyle, fitLabel, experienceLevel, sectorExperience,
				startupStages, specialties, recommendationReasons, recommendationEvidence, menteeCapacity, about,
				defaultMeetingDurationMinutes, meetingBufferMinutes, meetingFrequency, prepFileRequirement, languages,
				activeMenteeCount, completedMentorshipCount, programNames, programs, experiences, offerings, null);
	}

	// Recommendation ranking and its tests build profiles from the matching fields alone.
	public MentorProfileResponse(UUID id, String fullName, String email, String initials, String professionalTitle,
			String company, String availabilityLabel, String responseTimeLabel, String meetingMode,
			String communicationPreference, String mentoringStyle, String fitLabel, String experienceLevel,
			String sectorExperience, String startupStages, List<String> specialties,
			List<String> recommendationReasons, List<MentorEvidenceResponse> recommendationEvidence) {
		this(id, fullName, email, initials, professionalTitle, company, availabilityLabel, responseTimeLabel,
				meetingMode, communicationPreference, mentoringStyle, fitLabel, experienceLevel, sectorExperience,
				startupStages, specialties, recommendationReasons, recommendationEvidence, null, "", 45, 15, null,
				null, List.of(), 0, 0, List.of(), List.of(), List.of(), List.of());
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
				profile.getMenteeCapacity(), profile.getAbout(), profile.getDefaultMeetingDurationMinutes(),
				profile.getMeetingBufferMinutes(), profile.getMeetingFrequency(), profile.getPrepFileRequirement(),
				List.copyOf(profile.getLanguages()), 0, 0, List.of(), List.of(),
				profile.getExperiences().stream().map(MentorExperienceResponse::fromEntity).toList(),
				List.copyOf(profile.getOfferings()), StudentProfileResponse.fromEntity(profile));
	}

	public MentorProfileResponse withActivity(long activeMentees, long completedMentorships,
			List<MentorProgramResponse> joinedPrograms) {
		return new MentorProfileResponse(id, fullName, email, initials, professionalTitle, company,
				availabilityLabel, responseTimeLabel, meetingMode, communicationPreference, mentoringStyle, fitLabel,
				experienceLevel, sectorExperience, startupStages, specialties, recommendationReasons,
				recommendationEvidence, menteeCapacity, about, defaultMeetingDurationMinutes, meetingBufferMinutes,
				meetingFrequency, prepFileRequirement, languages, activeMentees, completedMentorships,
				joinedPrograms.stream().map(MentorProgramResponse::name).toList(), List.copyOf(joinedPrograms),
				experiences, offerings, studentProfile);
	}

	public MentorProfileResponse withRecommendation(List<String> reasons, List<MentorEvidenceResponse> evidence) {
		return new MentorProfileResponse(id, fullName, email, initials, professionalTitle, company,
				availabilityLabel, responseTimeLabel, meetingMode, communicationPreference, mentoringStyle, fitLabel,
				experienceLevel, sectorExperience, startupStages, specialties, reasons, evidence, menteeCapacity,
				about, defaultMeetingDurationMinutes, meetingBufferMinutes, meetingFrequency, prepFileRequirement,
				languages, activeMenteeCount, completedMentorshipCount, programNames, programs, experiences,
				offerings, studentProfile);
	}
}
