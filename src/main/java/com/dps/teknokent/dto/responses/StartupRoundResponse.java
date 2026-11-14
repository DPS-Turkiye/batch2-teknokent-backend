package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.entity.StartupRound;

public record StartupRoundResponse(
		UUID id,
		String title,
		String name,
		StartupStage stage,
		LocalDate startsOn,
		LocalDate endsOn,
		UUID phaseId) {

	/** {@code phaseId}: the program phase the round falls in, or null. */
	public static StartupRoundResponse fromEntity(StartupRound round, UUID phaseId) {
		return new StartupRoundResponse(round.getId(), round.getTitle(), round.getName(), round.getStage(),
				round.getStartsOn(), round.getEndsOn(), phaseId);
	}
}
