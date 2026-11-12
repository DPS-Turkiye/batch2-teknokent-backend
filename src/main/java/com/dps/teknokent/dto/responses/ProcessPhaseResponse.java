package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStageState;

/**
 * One card on Süreç: a phase of the team's program. Its state comes from its dates: COMPLETED once it has ended,
 * CURRENT while today falls in it, UPCOMING before it starts or while it has no dates.
 */
public record ProcessPhaseResponse(
		UUID id,
		String name,
		int position,
		LocalDate startDate,
		LocalDate endDate,
		String expectedOutput,
		StartupStageState state) {
}
