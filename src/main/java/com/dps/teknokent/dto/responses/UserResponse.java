package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

public record UserResponse(
		UUID id,
		String email,
		String fullName,
		UserRole role,
		UserAccountStatus status,
		UUID organizationId,
		String organizationName,
		String jobTitle,
		Instant signInsRevokedAt) {

	public static UserResponse fromEntity(AppUser user) {
		UserAccountStatus status = user.getStatus() == null
				? UserAccountStatus.APPROVED
				: user.getStatus();
		return new UserResponse(
				user.getId(), user.getEmail(), user.getFullName(), user.getRole(), status,
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				user.getOrganization() == null ? null : user.getOrganization().getName(), user.getJobTitle(),
				user.getSignInsRevokedAt());
	}
}
