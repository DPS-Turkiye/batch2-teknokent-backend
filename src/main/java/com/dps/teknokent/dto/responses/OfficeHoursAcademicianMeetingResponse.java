package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A booked office-hours meeting on the academician's calendar, with where it happens.
 *
 * @param courseName empty for a meeting booked from Menton
 */
public record OfficeHoursAcademicianMeetingResponse(
		UUID id,
		UUID requestId,
		Instant startsAt,
		Instant endsAt,
		@Nullable OfficeHoursStudentResponse student,
		@Nullable String topic,
		@Nullable String courseName,
		String place,
		String onlineLink) {
}
