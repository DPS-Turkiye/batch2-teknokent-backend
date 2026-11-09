package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

/**
 * An academician from the institution's records, for Admin › Kullanıcılar › Akademisyenler. Read-only: the
 * institution vouches for them, so nobody approves them by hand. {@code userId} stays null until they first sign in.
 */
public record AdminAcademicResponse(
		UUID personId,
		UUID userId,
		String fullName,
		String email,
		String academicTitle,
		String faculty,
		String departmentCode,
		String departmentName,
		boolean active,
		boolean signedIn,
		Instant lastLoginAt,
		long adviseeCount) {
}
