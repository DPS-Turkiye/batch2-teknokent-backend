package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProgramDetailResponse(
		UUID id,
		String name,
		UUID coordinatorId,
		String coordinatorName,
		Instant createdAt,
		List<ProgramStartupRosterResponse> startups,
		List<ProgramMentorResponse> mentors) {
}
