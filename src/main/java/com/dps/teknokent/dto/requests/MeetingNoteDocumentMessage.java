package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MeetingNoteDocumentMessage(
		@NotBlank @Size(max = 400000) String update,
		boolean fullState) {
}
