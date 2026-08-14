package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipStatus;

public record MentorshipResponse(
		UUID id,
		UUID mentorId,
		String mentorName,
		UUID organizationId,
		String organizationName,
		MentorshipStatus status,
		LocalDate startDate,
		LocalDate endDate,
		String goals) {
}
