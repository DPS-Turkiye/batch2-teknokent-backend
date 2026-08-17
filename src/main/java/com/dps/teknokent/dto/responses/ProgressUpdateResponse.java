package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ProgressStatus;
import com.dps.teknokent.entity.ProgressUpdate;

public record ProgressUpdateResponse(
		UUID id,
		UUID organizationId,
		UUID authorId,
		String authorName,
		String category,
		ProgressStatus status,
		String summary,
		String evidence,
		LocalDate recordedAt,
		Instant createdAt) {

	public static ProgressUpdateResponse fromEntity(ProgressUpdate update) {
		return new ProgressUpdateResponse(update.getId(), update.getOrganization().getId(), update.getAuthor().getId(),
				update.getAuthor().getFullName(), update.getCategory(), update.getStatus(), update.getSummary(),
				update.getEvidence(), update.getRecordedAt(), update.getCreatedAt());
	}
}
