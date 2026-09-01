package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record ProgramInvitationResponse(
		UUID programId,
		String programName,
		UUID coordinatorId,
		String coordinatorName,
		Instant createdAt) {
}
