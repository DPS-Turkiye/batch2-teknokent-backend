package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.entity.Organization;
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
		Instant updatedAt,
		UUID mentorshipRequestId,
		/** Who had the task before leaving the team, and when; both null for a task never handed over. */
		String previousOwnerName,
		Instant ownerLeftAt) {

	public static ActionItemResponse fromEntity(ActionItem actionItem) {
		return new ActionItemResponse(
				actionItem.getId(), actionItem.getOrganization().getId(), Organization.visibleName(actionItem.getOrganization()),
				actionItem.getMeeting() == null ? null : actionItem.getMeeting().getId(),
				actionItem.getOwner().getId(), actionItem.getOwner().getFullName(), actionItem.getTitle(),
				actionItem.getDescription(), actionItem.getStatus(), actionItem.getDueDate(),
				actionItem.getCompletionNotes(), actionItem.getCreatedAt(), actionItem.getUpdatedAt(),
				actionItem.getMentorshipRequest() == null ? null : actionItem.getMentorshipRequest().getId(),
				actionItem.getPreviousOwner() == null ? null : actionItem.getPreviousOwner().getFullName(),
				actionItem.getOwnerLeftAt());
	}
}
