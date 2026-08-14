package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReplyToMentorshipRequest(
		@NotNull UUID requesterId,
		@NotBlank @Size(max = 5000) String message) {
}
