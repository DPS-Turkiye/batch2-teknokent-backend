package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.UserRole;

public record UserResponse(
		UUID id,
		String email,
		String fullName,
		UserRole role,
		UUID organizationId,
		String organizationName,
		String jobTitle) {
}
