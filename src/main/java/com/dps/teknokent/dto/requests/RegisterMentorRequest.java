package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterMentorRequest(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Email @Size(max = 255) String email,
		@Size(max = 255) String jobTitle,
		@Size(max = 32) String phone) {
}
