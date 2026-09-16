package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

public record ProgramStartupRosterResponse(
		UUID id,
		String name,
		String description,
		List<ProgramMemberSummaryResponse> members) {
}
