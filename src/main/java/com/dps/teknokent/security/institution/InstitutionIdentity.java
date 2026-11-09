package com.dps.teknokent.security.institution;

import java.util.Set;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.StudyLevel;

/**
 * Who an institution's login says signed in. {@code persistentId} is the institution's stable id for the person,
 * never the e-mail, which can change; {@code emailVerified} says whether the institution vouches for the address.
 */
public record InstitutionIdentity(
		String persistentId,
		String email,
		boolean emailVerified,
		String fullName,
		Set<IdentityKind> kinds,
		String faculty,
		String departmentCode,
		String departmentName,
		Integer classYear,
		StudyLevel programLevel,
		String academicTitle,
		boolean iskurParticipant) {
}
