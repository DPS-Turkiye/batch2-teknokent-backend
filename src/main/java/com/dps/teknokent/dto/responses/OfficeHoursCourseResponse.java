package com.dps.teknokent.dto.responses;

import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursRole;

/** A course as one of its people sees it, with their role there. */
public record OfficeHoursCourseResponse(
		UUID courseId,
		String title,
		String shortName,
		OfficeHoursRole role,
		String userFullName) {
}
