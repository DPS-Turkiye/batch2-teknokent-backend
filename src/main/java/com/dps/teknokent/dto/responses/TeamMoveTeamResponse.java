package com.dps.teknokent.dto.responses;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** A team on the move screen, with the program it is shown under; an admin may see a team in no program. */
public record TeamMoveTeamResponse(
		UUID organizationId,
		String name,
		@Nullable UUID programId,
		@Nullable String programName) {
}
