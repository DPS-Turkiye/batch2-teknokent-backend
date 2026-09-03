package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterFounderRequest(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(max = 255) String organizationName,
		@Size(max = 5000) String description,
		@Size(max = 255) String jobTitle,
		@Size(max = 32) String phone,
		// Optional: null is "no program", which is a real answer, not a missing one.
		UUID programId,
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
		List<@Size(max = 255) String> supportAreas) {
}
