package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ActionItemStatus;

public record ActionItemResponse(
		UUID id,
		UUID organizationId,
		String organizationName,
		UUID meetingId,
		UUID ownerId,
		String ownerName,
		String title,
		String description,
		ActionItemStatus status,
		LocalDate dueDate,
		String completionNotes,
		Instant createdAt,
		Instant updatedAt) {
}
