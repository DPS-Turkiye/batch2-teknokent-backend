package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A document on the Dosyalar tab, with who uploaded it, its meeting and who it is shared with. */
public record OrganizationDocumentResponse(
		UUID id,
		String kind,
		String originalFilename,
		String contentType,
		long sizeBytes,
		Instant createdAt,
		UUID uploadedById,
		String uploadedByName,
		UUID meetingId,
		String meetingAgenda,
		Instant meetingScheduledAt,
		Instant reviewedAt,
		UUID reviewedById,
		String reviewedByName,
		List<DocumentShareResponse> shares) {
}
