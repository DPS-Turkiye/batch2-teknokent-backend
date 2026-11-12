package com.dps.teknokent.dto.responses;

import java.util.UUID;

/**
 * An academician a Menton student may ask for office hours: only those who chose to be listed. Name, title,
 * department and faculty come from ODTÜ's record; the rest is what they set.
 *
 * @param id             their office-hours id, used to ask for their slots and to send a request
 * @param meetingMinutes their usual meeting length
 * @param contactEmail   only when they filled it in
 */
public record OfficeHoursAcademicianResponse(
		UUID id,
		String fullName,
		String academicTitle,
		String department,
		String faculty,
		String place,
		String onlineLink,
		String contactEmail,
		int meetingMinutes) {
}
