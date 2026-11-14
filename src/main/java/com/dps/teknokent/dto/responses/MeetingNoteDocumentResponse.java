package com.dps.teknokent.dto.responses;

import org.jspecify.annotations.Nullable;

public record MeetingNoteDocumentResponse(
		@Nullable String state,
		boolean seedFromSnapshot) {
}
