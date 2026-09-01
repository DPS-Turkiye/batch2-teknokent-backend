package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProgramResponse(
		UUID id,
		String name,
		UUID coordinatorId,
		String coordinatorName,
		Instant createdAt,
		List<OrganizationResponse> startups,
		List<ProgramMentorResponse> mentors) {
}
