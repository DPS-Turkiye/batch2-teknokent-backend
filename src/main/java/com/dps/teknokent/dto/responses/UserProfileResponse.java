package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.ContactPreference;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.AppUser;

// Separate from UserResponse, which backs /api/auth/me and the session cookie;
// widening that would put a bio and LinkedIn URL into every response using it.
public record UserProfileResponse(
		UUID id,
		String fullName,
		String email,
		String jobTitle,
		String phone,
		String bio,
		String linkedinUrl,
		List<String> expertise,
		List<ContactPreference> contactPreferences) {

	public static UserProfileResponse fromEntity(AppUser user) {
		return new UserProfileResponse(
				user.getId(), user.getFullName(), user.getEmail(), user.getJobTitle(), user.getPhone(),
				user.getBio(), user.getLinkedinUrl(), List.copyOf(user.getExpertise()),
				List.copyOf(user.getContactPreferences()));
	}

	/** Without the phone number and contact preferences, for someone other than the person or an admin. */
	public static UserProfileResponse withoutContactDetails(AppUser user) {
		return new UserProfileResponse(
				user.getId(), user.getFullName(), user.getEmail(), user.getJobTitle(), null,
				user.getBio(), user.getLinkedinUrl(), List.copyOf(user.getExpertise()), List.of());
	}
}
