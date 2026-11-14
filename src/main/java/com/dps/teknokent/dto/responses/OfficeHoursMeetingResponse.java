package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.OfficeHoursMeetingAction;

public record OfficeHoursMeetingResponse(
		UUID id,
		UUID requestId,
		Instant scheduledAt,
		int durationMinutes,
		OfficeHoursPersonResponse student,
		OfficeHoursPersonResponse instructor,
		String topic,
		/** {@code scheduledAt} and its end, with Turkey's offset. */
		OfficeHoursTimeResponse time,
		/** The course's title from Moodle. */
		String courseName,
		/** What the caller may still do with the meeting. */
		List<OfficeHoursMeetingAction> allowedActions) {
}
