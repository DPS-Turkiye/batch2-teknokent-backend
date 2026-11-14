package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** {@code mine}: the viewer wrote it, so they may delete it. */
public record CoordinatorNoteResponse(
		UUID id,
		UUID mentorshipId,
		@Nullable String authorName,
		String body,
		Instant createdAt,
		boolean mine) {
}
