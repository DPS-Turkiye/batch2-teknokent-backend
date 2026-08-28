package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterMemberRequest(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(max = 32) String inviteCode,
		@Size(max = 255) String jobTitle,
		@Size(max = 32) String phone) {
}
