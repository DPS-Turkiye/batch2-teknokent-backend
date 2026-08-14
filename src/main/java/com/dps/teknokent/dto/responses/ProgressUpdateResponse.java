package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ProgressStatus;

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
}
