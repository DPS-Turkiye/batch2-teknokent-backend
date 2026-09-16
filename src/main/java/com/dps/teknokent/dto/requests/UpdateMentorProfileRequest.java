package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.Valid;
import java.util.List;

import com.dps.teknokent.domain.MeetingFrequency;
import com.dps.teknokent.domain.MentorLanguage;
import com.dps.teknokent.domain.PrepFileRequirement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMentorProfileRequest(
		@NotBlank @Size(max = 200) String professionalTitle,
		@NotBlank @Size(max = 200) String company,
		@NotNull @Size(max = 200) String availabilityLabel,
		@NotNull @Size(max = 200) String responseTimeLabel,
		@NotNull @Size(max = 200) String meetingMode,
		@NotNull @Size(max = 200) String communicationPreference,
		@NotBlank @Size(max = 200) String mentoringStyle,
		@NotNull @Size(max = 200) String fitLabel,
		@NotBlank @Size(max = 200) String experienceLevel,
		@NotBlank @Size(max = 200) String sectorExperience,
		@NotBlank @Size(max = 200) String startupStages,
		@NotNull List<@NotBlank String> specialties,
		@Min(1) @Max(50) Integer menteeCapacity,
		@Size(max = 10) List<@Valid MentorExperienceRequest> experiences,
		@Size(max = 4000) String about,
		MeetingFrequency meetingFrequency,
		PrepFileRequirement prepFileRequirement,
		@Size(max = 7) List<@NotNull MentorLanguage> languages,
		@Size(max = 50) List<@NotNull @Valid MentorProgramRoleRequest> programRoles) {

	/** Keeps older clients and focused tests source-compatible while about rolls out. */
	public UpdateMentorProfileRequest(
			String professionalTitle, String company, String availabilityLabel, String responseTimeLabel,
			String meetingMode, String communicationPreference, String mentoringStyle, String fitLabel,
			String experienceLevel, String sectorExperience, String startupStages, List<String> specialties,
			Integer menteeCapacity, List<MentorExperienceRequest> experiences) {
		this(professionalTitle, company, availabilityLabel, responseTimeLabel, meetingMode, communicationPreference,
				mentoringStyle, fitLabel, experienceLevel, sectorExperience, startupStages, specialties,
				menteeCapacity, experiences, null, null, null, null, null);
	}
}
