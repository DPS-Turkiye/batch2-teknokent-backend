package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.Program;
import com.dps.teknokent.entity.ProgramMentorRemoval;

/** A mentor's removal from a program; {@code withdrawnRequests} is how many open requests it closed. */
public record ProgramMentorRemovalResponse(
		UUID id,
		UUID mentorId,
		UUID programId,
		String programName,
		Instant removedAt,
		int withdrawnRequests) {

	public static ProgramMentorRemovalResponse of(ProgramMentorRemoval removal, Program program,
			int withdrawnRequests) {
		return new ProgramMentorRemovalResponse(removal.getId(), removal.getMentorId(), program.getId(),
				program.getName(), removal.getRemovedAt(), withdrawnRequests);
	}
}
