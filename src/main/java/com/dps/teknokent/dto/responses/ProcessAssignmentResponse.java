package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.entity.ActionItem;

public record ProcessAssignmentResponse(
		UUID id,
		UUID roundId,
		StartupStage stage,
		String title,
		String description,
		ActionItemStatus status,
		LocalDate dueDate,
		UUID submissionDocumentId,
		String submissionFilename,
		Instant createdAt) {

	public static ProcessAssignmentResponse fromEntity(ActionItem item, String submissionFilename) {
		return new ProcessAssignmentResponse(item.getId(), item.getRound() == null ? null : item.getRound().getId(),
				item.getStage(), item.getTitle(), item.getDescription(), item.getStatus(), item.getDueDate(),
				item.getSubmissionDocumentId(), submissionFilename, item.getCreatedAt());
	}
}
