package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;

/**
 * One of a Menton student's own office-hours requests, as their Randevularım shows it.
 *
 * @param time           the booked meeting once accepted, otherwise the time they asked for
 * @param proposedTimes  the academician's other times while the request waits for the student
 * @param courseName     empty for a request made from Menton
 * @param allowedActions what the student may still do with it
 */
public record OfficeHoursStudentRequestResponse(
		UUID id,
		OfficeHoursRequestStatus status,
		Academician academician,
		String topic,
		String description,
		String courseName,
		OfficeHoursTimeResponse time,
		List<OfficeHoursTimeResponse> proposedTimes,
		String academicianMessage,
		String rejectionReason,
		String place,
		String onlineLink,
		Instant requestedAt,
		List<OfficeHoursRequestAction> allowedActions) {

	/** Who the request went to, as ODTÜ's record names them. */
	public record Academician(UUID id, String fullName, String academicTitle, String department) {
	}
}
