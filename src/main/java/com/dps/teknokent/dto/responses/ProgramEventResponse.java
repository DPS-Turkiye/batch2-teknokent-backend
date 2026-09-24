package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramEventKind;

public record ProgramEventResponse(
		UUID id,
		UUID programId,
		String title,
		ProgramEventKind kind,
		Instant startsAt,
		Instant endsAt,
		String description) {
}
