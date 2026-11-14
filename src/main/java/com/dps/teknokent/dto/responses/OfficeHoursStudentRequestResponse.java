package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;

/**
 * One of a Menton student's own office-hours requests, as their Randevularım shows it.
 *
 * @param time           the booked meeting once accepted, otherwise the time they asked for
 * @param proposedTimes  the academician's other times while the request waits for the student
 * @param courseName     empty for a request made from Menton
 * @param allowedActions what the student may still do with it
 * @param cancellation   how the meeting was called off; empty while it is on
 */
public record OfficeHoursStudentRequestResponse(
		UUID id,
		OfficeHoursRequestStatus status,
		Academician academician,
		String topic,
		String description,
		@Nullable String courseName,
		@Nullable OfficeHoursTimeResponse time,
		List<OfficeHoursTimeResponse> proposedTimes,
		String academicianMessage,
		String rejectionReason,
		@Nullable String place,
		@Nullable String onlineLink,
		Instant requestedAt,
		List<OfficeHoursRequestAction> allowedActions,
		@Nullable OfficeHoursCancellationResponse cancellation) {

	/** Who the request went to, as ODTÜ's record names them. */
	public record Academician(UUID id, @Nullable String fullName, @Nullable String academicTitle,
			@Nullable String department) {
	}
}
