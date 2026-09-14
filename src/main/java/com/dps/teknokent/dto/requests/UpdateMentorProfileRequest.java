package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.Valid;
import java.util.List;

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
		@Size(max = 10) List<@Valid MentorExperienceRequest> experiences) {
}
