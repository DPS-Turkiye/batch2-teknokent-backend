package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

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
		OfficeHoursStudentResponse student,
		String topic,
		String courseName,
		String place,
		String onlineLink) {
}
