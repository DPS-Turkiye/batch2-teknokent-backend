package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

/** {@code teamCount} and {@code mentorCount}: who the program's announcements reach today. */
public record ProgramAnnouncementResponse(
		UUID id,
		UUID programId,
		String programName,
		String authorName,
		String body,
		Instant createdAt,
		int teamCount,
		int mentorCount) {
}
