package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record DocumentRequestItemResponse(
		UUID id,
		String title,
		int sortOrder,
		LocalDate dueDate,
		UUID organizationId,
		UUID mentorshipRequestId,
		UUID meetingId,
		UUID mentorshipId,
		UUID programId,
		UUID createdById,
		String createdByName,
		Instant createdAt,
		List<DocumentRequestUploadResponse> uploads) {
}
