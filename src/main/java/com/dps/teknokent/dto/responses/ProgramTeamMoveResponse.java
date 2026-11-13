package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramTeamMove;

/** A team's move between two programs; {@code withdrawnRequests} is how many open requests the move closed. */
public record ProgramTeamMoveResponse(
		UUID id,
		UUID organizationId,
		UUID fromProgramId,
		String fromProgramName,
		UUID toProgramId,
		String toProgramName,
		Instant movedAt,
		int withdrawnRequests) {

	public static ProgramTeamMoveResponse of(ProgramTeamMove move, Program from, Program to, int withdrawnRequests) {
		return new ProgramTeamMoveResponse(move.getId(), move.getOrganizationId(), from.getId(), from.getName(),
				to.getId(), to.getName(), move.getMovedAt(), withdrawnRequests);
	}
}
