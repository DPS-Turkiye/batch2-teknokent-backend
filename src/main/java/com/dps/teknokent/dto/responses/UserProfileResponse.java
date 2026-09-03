package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.AppUser;

/**
 * A person's own profile — the wizard's first step, read back.
 *
 * <p>
 * Separate from {@link UserResponse} on purpose. That shape is returned by
 * {@code /api/auth/me}, the organization member list and the admin account list,
 * and it backs the session cookie; widening it would put a bio and a LinkedIn
 * URL into every one of those responses. The bio, LinkedIn and expertise added
 * in V33 have been write-only since: collected at signup, stored, and never
 * shown again.
 */
public record UserProfileResponse(
		UUID id,
		String fullName,
		String email,
		String jobTitle,
		String phone,
		String bio,
		String linkedinUrl,
		List<String> expertise) {

	public static UserProfileResponse fromEntity(AppUser user) {
		return new UserProfileResponse(
				user.getId(), user.getFullName(), user.getEmail(), user.getJobTitle(), user.getPhone(),
				user.getBio(), user.getLinkedinUrl(), List.copyOf(user.getExpertise()));
	}
}
