package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A person editing their own profile after signup — the wizard's first step,
 * reopened.
 *
 * <p>
 * Email and role are deliberately absent. Email is the key
 * {@code FirebaseBearerFilter} falls back to when matching a token to an
 * account, so changing it here would silently break sign-in; role and status are
 * the coordinator's to decide.
 */
public record UpdateUserProfileRequest(
		@NotNull UUID actorId,
		@NotBlank @Size(max = 255) String fullName,
		@Size(max = 255) String jobTitle,
		@Size(max = 32) String phone,
		@Size(max = 5000) String bio,
		@Size(max = 255) String linkedinUrl,
		List<@Size(max = 255) String> expertise) {
}
