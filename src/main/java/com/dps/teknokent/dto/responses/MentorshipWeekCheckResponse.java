package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.UUID;

public record MentorshipWeekCheckResponse(
		UUID mentorshipId,
		String organizationName,
		int daysPerWeek,
		LocalDate startDate,
		LocalDate endDate,
		LocalDate weekStart,
		boolean completed) {
}
