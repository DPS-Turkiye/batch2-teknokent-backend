package com.dps.teknokent.dto.responses;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.CoordinatorCandidateStatus;
import com.dps.teknokent.domain.UserRole;

/** {@code userId}, {@code fullName} and {@code role} are null for a NEW email. */
public record CoordinatorCandidateResponse(
		String email,
		CoordinatorCandidateStatus status,
		@Nullable UUID userId,
		@Nullable String fullName,
		@Nullable UserRole role) {
}
