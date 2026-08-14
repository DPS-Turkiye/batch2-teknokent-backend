package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record ScheduleMentorshipRequest(
		@NotNull UUID requesterId,
		@NotNull UUID slotId) {
}
