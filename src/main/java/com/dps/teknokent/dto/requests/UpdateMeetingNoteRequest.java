package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMeetingNoteRequest(
		@NotNull UUID organizationId,
		@NotNull UUID userId,
		@NotNull @Size(max = 200000) String content,
		@NotNull @Min(0) Long version) {
}
