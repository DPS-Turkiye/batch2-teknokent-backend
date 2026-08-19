package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;

public record UserResponse(
		UUID id,
		String email,
		String fullName,
		UserRole role,
		UUID organizationId,
		String organizationName,
		String jobTitle) {

	public static UserResponse fromEntity(AppUser user) {
		return new UserResponse(
				user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
				user.getOrganization() == null ? null : user.getOrganization().getId(),
				user.getOrganization() == null ? null : user.getOrganization().getName(), user.getJobTitle());
	}
}
