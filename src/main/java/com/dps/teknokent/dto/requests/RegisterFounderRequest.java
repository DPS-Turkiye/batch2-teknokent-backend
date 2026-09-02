package com.dps.teknokent.dto.requests;

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
		UUID programId) {
}
