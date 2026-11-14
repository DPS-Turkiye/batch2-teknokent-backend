package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** {@code teamCount} and {@code mentorCount}: who the program's announcements reach today. */
public record ProgramAnnouncementResponse(
		UUID id,
		UUID programId,
		@Nullable String programName,
		@Nullable String authorName,
		String body,
		Instant createdAt,
		int teamCount,
		int mentorCount) {
}
