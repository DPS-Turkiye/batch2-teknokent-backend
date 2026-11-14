package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.ActionItemStatus;
import com.dps.teknokent.domain.StartupStage;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.ActionItem;
import com.dps.teknokent.entity.AppUser;

public record ProcessAssignmentResponse(
		UUID id,
		@Nullable UUID roundId,
		StartupStage stage,
		String title,
		String description,
		ActionItemStatus status,
		LocalDate dueDate,
		UUID submissionDocumentId,
		@Nullable String submissionFilename,
		@Nullable String createdByName,
		@Nullable UserRole createdByRole,
		Instant createdAt,
		@Nullable UUID phaseId) {

	/** {@code phaseId}: the program phase the Ödev belongs to, or null ("Fazsız görevler"). */
	public static ProcessAssignmentResponse fromEntity(ActionItem item, @Nullable String submissionFilename,
			@Nullable UUID phaseId) {
		AppUser creator = item.getCreatedBy();
		return new ProcessAssignmentResponse(item.getId(), item.getRound() == null ? null : item.getRound().getId(),
				item.getStage(), item.getTitle(), item.getDescription(), item.getStatus(), item.getDueDate(),
				item.getSubmissionDocumentId(), submissionFilename, creator == null ? null : creator.getFullName(),
				creator == null ? null : creator.getRole(), item.getCreatedAt(), phaseId);
	}
}
