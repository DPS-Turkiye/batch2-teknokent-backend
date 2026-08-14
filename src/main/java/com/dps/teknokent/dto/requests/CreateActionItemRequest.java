package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateActionItemRequest(
		@NotNull UUID organizationId,
		UUID meetingId,
		@NotNull UUID ownerId,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 10000) String description,
		LocalDate dueDate) {
}
