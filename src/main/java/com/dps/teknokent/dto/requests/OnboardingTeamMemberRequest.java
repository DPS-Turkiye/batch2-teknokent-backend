package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OnboardingTeamMemberRequest(
		@NotBlank @Size(max = 255) String fullName,
		@Size(max = 255) String jobTitle,
		@Email @Size(max = 255) String email) {
}
