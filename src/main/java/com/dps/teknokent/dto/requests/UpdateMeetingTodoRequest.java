package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMeetingTodoRequest(
		@NotNull UUID organizationId,
		@Size(max = 1000) String title,
		Boolean done) {
}
