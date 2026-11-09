package com.dps.teknokent.security;

import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;

/** {@code role} is the primary role, kept until every reader uses {@code permissions}. */
public record AppUserPrincipal(
		UUID id,
		String email,
		UserRole role,
		Set<UserRole> permissions,
		UserAccountStatus status,
		String firebaseUid) {

	public AppUserPrincipal(UUID id, String email, UserRole role, UserAccountStatus status, String firebaseUid) {
		this(id, email, role, Set.of(role), status, firebaseUid);
	}

	public AppUserPrincipal {
		permissions = Set.copyOf(permissions);
	}
}
