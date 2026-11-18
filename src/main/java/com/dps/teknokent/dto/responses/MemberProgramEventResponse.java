package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.ProgramCalendarEntryKind;
import com.dps.teknokent.entity.ProgramEvent;

/**
 * A program entry in a participant's calendar. {@code plan} is set for a {@code SESSION} or {@code SPECIAL_DAY}; a
 * session's {@code id} is derived from the plan and stays the same between reads.
 */
public record MemberProgramEventResponse(
		UUID id,
		UUID programId,
		@Nullable String programName,
		String title,
		ProgramCalendarEntryKind kind,
		Instant startsAt,
		@Nullable Instant endsAt,
		@Nullable String description,
		@Nullable ProgramCalendarPlanItem plan) {

	public static MemberProgramEventResponse of(ProgramEvent event, @Nullable String programName) {
		return new MemberProgramEventResponse(
				event.getId(),
				event.getProgramId(),
				programName,
				event.getTitle(),
				ProgramCalendarEntryKind.of(event.getKind()),
				event.getStartsAt(),
				event.getEndsAt(),
				event.getDescription(),
				null);
	}
}
