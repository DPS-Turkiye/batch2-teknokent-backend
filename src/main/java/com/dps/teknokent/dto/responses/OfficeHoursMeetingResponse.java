package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record OfficeHoursMeetingResponse(
		UUID id,
		UUID requestId,
		Instant scheduledAt,
		int durationMinutes,
		OfficeHoursPersonResponse student,
		OfficeHoursPersonResponse instructor,
		String topic) {
}
