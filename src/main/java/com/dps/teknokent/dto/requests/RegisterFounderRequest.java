package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import com.dps.teknokent.domain.ContactPreference;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterFounderRequest(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Email @Size(max = 255) String email,
		// Required except in an individual-mentee program, where the team is named after the person; checked in
		// RegistrationService (STARTUP_REQUIRED).
		@Size(max = 255) String organizationName,
		@Size(max = 5000) String description,
		@Size(max = 255) String jobTitle,
		@Size(max = 32) @Pattern(regexp = "^\\+?[0-9]{8,15}$") String phone,
		@NotNull UUID programId,
		@Size(max = 5000) String bio,
		@Size(max = 255) String linkedinUrl,
		List<@Size(max = 255) String> expertise,
		@Size(max = 255) String website,
		@Size(max = 5000) String problem,
		@Size(max = 5000) String solution,
		@Size(max = 5000) String currentSolution,
		@Size(max = 5000) String targetUser,
		@Size(max = 255) String sector,
		@Size(max = 255) String maturity,
		@Size(max = 255) String programLevel,
		List<@Size(max = 255) String> supportAreas,
		@Size(max = 4) List<@NotNull ContactPreference> contactPreferences,
		@Size(max = 255) String mvpUrl,
		@Size(max = 20) List<@Valid OnboardingTeamMemberRequest> teamMembers,
		@Min(1900) @Max(2100) Integer foundedYear) implements StartupProfileFields {
}
