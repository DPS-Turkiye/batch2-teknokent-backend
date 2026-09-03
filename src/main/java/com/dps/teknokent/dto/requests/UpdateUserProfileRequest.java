package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Email is absent: FirebaseBearerFilter matches accounts by it, so changing it
// here would break sign-in. Role/status are the coordinator's, not the owner's.
public record UpdateUserProfileRequest(
		@NotNull UUID actorId,
		@NotBlank @Size(max = 255) String fullName,
		@Size(max = 255) String jobTitle,
		@Size(max = 32) String phone,
		@Size(max = 5000) String bio,
		@Size(max = 255) String linkedinUrl,
		List<@Size(max = 255) String> expertise) {
}
