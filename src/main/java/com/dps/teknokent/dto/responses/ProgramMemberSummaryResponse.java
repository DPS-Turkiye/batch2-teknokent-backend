package com.dps.teknokent.dto.responses;

import java.util.UUID;

public record ProgramMemberSummaryResponse(
		UUID id,
		String fullName,
		String jobTitle,
		boolean founder) {
}
