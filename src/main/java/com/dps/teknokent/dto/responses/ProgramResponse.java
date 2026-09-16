package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramStatus;

public record ProgramResponse(
		UUID id,
		String name,
		UUID coordinatorId,
		String coordinatorName,
		Instant createdAt,
		ProgramStatus status,
		List<OrganizationResponse> startups,
		List<ProgramMentorResponse> mentors) {
}
