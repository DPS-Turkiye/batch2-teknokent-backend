package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.OfficeHoursRole;

/**
 * A request as the academician it was sent to sees it, from a Moodle course or from Menton.
 *
 * @param courseName     the course's title; empty for a request made from Menton
 * @param meeting        the booked time once accepted
 * @param allowedActions what the academician may still answer
 */
public record OfficeHoursInboxRequestResponse(
		UUID id,
		OfficeHoursRequestStatus status,
		OfficeHoursStudentResponse student,
		String topic,
		String note,
		String courseName,
		List<OfficeHoursTimeResponse> requestedTimes,
		List<OfficeHoursTimeResponse> proposedTimes,
		List<Message> messages,
		OfficeHoursTimeResponse meeting,
		String rejectionReason,
		Instant requestedAt,
		List<OfficeHoursRequestAction> allowedActions) {

	/** A question from the academician or the student's answer. */
	public record Message(OfficeHoursRole author, String body, Instant sentAt) {
	}
}
