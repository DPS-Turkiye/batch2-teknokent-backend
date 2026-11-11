package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursRole;

/** Who launched and from which course; the frontend keeps the user in its office-hours session. */
public record LtiLaunchResponse(
		UUID userId,
		String fullName,
		UUID courseId,
		String courseTitle,
		OfficeHoursRole role) {
}
