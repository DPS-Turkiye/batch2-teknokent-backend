package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.UUID;

public record ProgramPhaseResponse(
		UUID id,
		String name,
		int position,
		LocalDate startDate,
		LocalDate endDate,
		String expectedOutput) {
}
