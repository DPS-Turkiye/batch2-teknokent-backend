package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.ProgramEventKind;
import com.dps.teknokent.entity.ProgramEvent;

public record MemberProgramEventResponse(
		UUID id,
		UUID programId,
		@Nullable String programName,
		String title,
		ProgramEventKind kind,
		Instant startsAt,
		Instant endsAt,
		String description) {

	public static MemberProgramEventResponse of(ProgramEvent event, @Nullable String programName) {
		return new MemberProgramEventResponse(
				event.getId(),
				event.getProgramId(),
				programName,
				event.getTitle(),
				event.getKind(),
				event.getStartsAt(),
				event.getEndsAt(),
				event.getDescription());
	}
}
