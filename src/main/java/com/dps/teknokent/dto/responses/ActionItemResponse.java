package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.entity.ActionItem;

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

	public static ActionItemResponse fromEntity(ActionItem actionItem) {
		return new ActionItemResponse(
				actionItem.getId(), actionItem.getOrganization().getId(), actionItem.getOrganization().getName(),
				actionItem.getMeeting() == null ? null : actionItem.getMeeting().getId(),
				actionItem.getOwner().getId(), actionItem.getOwner().getFullName(), actionItem.getTitle(),
				actionItem.getDescription(), actionItem.getStatus(), actionItem.getDueDate(),
				actionItem.getCompletionNotes(), actionItem.getCreatedAt(), actionItem.getUpdatedAt());
	}
}
