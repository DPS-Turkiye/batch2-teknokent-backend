package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.entity.MeetingNote;

public record MeetingNoteResponse(
		UUID meetingId,
		String content,
		Long version,
		@Nullable UUID updatedById,
		@Nullable String updatedByName,
		Instant updatedAt) {

	public static MeetingNoteResponse fromEntity(MeetingNote note) {
		return new MeetingNoteResponse(
				note.getMeeting().getId(),
				note.getContent(),
				note.getVersion(),
				note.getUpdatedBy() == null ? null : note.getUpdatedBy().getId(),
				note.getUpdatedBy() == null ? null : note.getUpdatedBy().getFullName(),
				note.getUpdatedAt());
	}
}
