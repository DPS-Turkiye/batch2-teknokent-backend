package com.dps.teknokent.security;

import java.util.UUID;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;

public record AppUserPrincipal(
		UUID id,
		String email,
		UserRole role,
		UserAccountStatus status,
		String firebaseUid) {
}
