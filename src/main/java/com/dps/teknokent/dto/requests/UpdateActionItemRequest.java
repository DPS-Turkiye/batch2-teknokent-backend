package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ActionItemStatus;

import jakarta.validation.constraints.Size;

public record UpdateActionItemRequest(
		UUID ownerId,
		@Size(max = 255) String title,
		@Size(max = 10000) String description,
		ActionItemStatus status,
		LocalDate dueDate,
		@Size(max = 10000) String completionNotes) {
}
