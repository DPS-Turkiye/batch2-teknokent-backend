package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

/**
 * {@code individualMentee}: a mentee whose current program takes students as teams of one, so their screens drop the
 * startup wording. Filled by /api/auth/me ({@code AppUserService.findSignedIn}) and founder sign-up; false elsewhere.
 */
public record UserResponse(
		UUID id,
		String email,
		String fullName,
		UserRole role,
		UserAccountStatus status,
		UUID organizationId,
		String organizationName,
		String jobTitle,
		Instant signInsRevokedAt,
		boolean individualMentee) {

	public static UserResponse fromEntity(AppUser user) {
		return fromEntity(user, false);
	}

	public static UserResponse fromEntity(AppUser user, boolean individualMentee) {
		UserAccountStatus status = user.getStatus() == null
				? UserAccountStatus.APPROVED
				: user.getStatus();
		return new UserResponse(
				user.getId(), user.getEmail(), user.getFullName(), user.getRole(), status,
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				user.getOrganization() == null ? null : user.getOrganization().getName(), user.getJobTitle(),
				user.getSignInsRevokedAt(), individualMentee);
	}
}
