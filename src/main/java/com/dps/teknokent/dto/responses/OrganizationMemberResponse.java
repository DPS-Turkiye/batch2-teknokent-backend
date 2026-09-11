package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;

/** A {@link UserResponse} plus what only makes sense relative to the team. */
public record OrganizationMemberResponse(
		UUID id,
		String email,
		String fullName,
		UserRole role,
		UserAccountStatus status,
		UUID organizationId,
		String organizationName,
		String jobTitle,
		boolean founder,
		Instant joinedAt) {

	public static OrganizationMemberResponse fromEntity(AppUser user, Organization organization) {
		UserAccountStatus status = user.getStatus() == null
				? UserAccountStatus.APPROVED
				: user.getStatus();
		boolean founder = organization.getFounder() != null
				&& organization.getFounder().getId().equals(user.getId());
		return new OrganizationMemberResponse(
				user.getId(), user.getEmail(), user.getFullName(), user.getRole(), status,
				organization.getId(), organization.getName(), user.getJobTitle(), founder, user.getCreatedAt());
	}
}
