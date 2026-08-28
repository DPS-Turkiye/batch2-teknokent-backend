package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Exactly one of {@code organizationId} or {@code mentorshipRequestId} identifies
 * the owning organization; see {@code ActionItemService#resolveOrganizationId}.
 */
public record CreateActionItemRequest(
		UUID organizationId,
		UUID mentorshipRequestId,
		UUID meetingId,
		@NotNull UUID ownerId,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 10000) String description,
		LocalDate dueDate) {
}
