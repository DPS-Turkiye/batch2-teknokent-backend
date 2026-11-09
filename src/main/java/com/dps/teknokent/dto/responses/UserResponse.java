package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

/**
 * {@code individualMentee}: a mentee whose current program takes students as teams of one, so their screens drop the
 * startup wording. Filled by /api/auth/me ({@code AppUserService.findSignedIn}) and founder sign-up; false elsewhere.
 * {@code permissions}: every active permission, in {@link UserRole} order; {@code role} is the primary one.
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
		boolean individualMentee,
		List<UserRole> permissions,
		List<IdentityKind> institutionKinds) {

	public UserResponse(UUID id, String email, String fullName, UserRole role, UserAccountStatus status,
			UUID organizationId, String organizationName, String jobTitle, Instant signInsRevokedAt,
			boolean individualMentee) {
		this(id, email, fullName, role, status, organizationId, organizationName, jobTitle, signInsRevokedAt,
				individualMentee, role == null ? List.of() : List.of(role));
	}

	public UserResponse(UUID id, String email, String fullName, UserRole role, UserAccountStatus status,
			UUID organizationId, String organizationName, String jobTitle, Instant signInsRevokedAt,
			boolean individualMentee, List<UserRole> permissions) {
		this(id, email, fullName, role, status, organizationId, organizationName, jobTitle, signInsRevokedAt,
				individualMentee, permissions, List.of());
	}

	/** {@code institutionKinds}: what an institution (ODTÜ) says this person is; empty for everyone else. Filled by /me. */
	public UserResponse withInstitutionKinds(List<IdentityKind> kinds) {
		return new UserResponse(id, email, fullName, role, status, organizationId, organizationName, jobTitle,
				signInsRevokedAt, individualMentee, permissions, List.copyOf(kinds));
	}

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
				user.getSignInsRevokedAt(), individualMentee, List.copyOf(user.activePermissions()));
	}
}
