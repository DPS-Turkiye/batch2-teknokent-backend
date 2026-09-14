package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentRequestItemResponse(
		UUID id,
		String title,
		int sortOrder,
		UUID organizationId,
		UUID mentorshipRequestId,
		UUID meetingId,
		UUID mentorshipId,
		UUID createdById,
		Instant createdAt,
		List<DocumentRequestUploadResponse> uploads) {
}
