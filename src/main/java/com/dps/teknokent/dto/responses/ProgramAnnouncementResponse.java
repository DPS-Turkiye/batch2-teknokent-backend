package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AnnouncementAudience;

/**
 * {@code teamCount} and {@code mentorCount}: who this announcement reaches today, so the group its audience leaves
 * out counts 0. {@code individualProgram}: the program's teams are single participants (İŞKUR), which the screens
 * name "katılımcılar" instead of "takımlar".
 */
public record ProgramAnnouncementResponse(
		UUID id,
		UUID programId,
		@Nullable String programName,
		@Nullable String authorName,
		String body,
		Instant createdAt,
		AnnouncementAudience audience,
		boolean individualProgram,
		int teamCount,
		int mentorCount) {
}
