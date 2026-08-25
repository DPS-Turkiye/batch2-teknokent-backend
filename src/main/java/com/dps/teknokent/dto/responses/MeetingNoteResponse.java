package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.MeetingNote;

public record MeetingNoteResponse(
		UUID meetingId,
		String content,
		Long version,
		UUID updatedById,
		String updatedByName,
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
