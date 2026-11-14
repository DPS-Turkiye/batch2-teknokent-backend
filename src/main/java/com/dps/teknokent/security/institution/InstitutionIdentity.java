package com.dps.teknokent.security.institution;

import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.StudyLevel;

/**
 * Who an institution's login says signed in. {@code persistentId} is the institution's stable id for the person,
 * never the e-mail, which can change; {@code emailVerified} says whether the institution vouches for the address.
 */
public record InstitutionIdentity(
		String persistentId,
		@Nullable String email,
		boolean emailVerified,
		@Nullable String fullName,
		Set<IdentityKind> kinds,
		@Nullable String faculty,
		@Nullable String departmentCode,
		@Nullable String departmentName,
		@Nullable Integer classYear,
		@Nullable StudyLevel programLevel,
		@Nullable String academicTitle,
		boolean iskurParticipant) {
}
