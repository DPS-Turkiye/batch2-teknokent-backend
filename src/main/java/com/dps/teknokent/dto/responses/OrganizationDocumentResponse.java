package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.DocumentKind;

/** A document on the Dosyalar tab, with who uploaded it, its meeting and who it is shared with. */
public record OrganizationDocumentResponse(
		UUID id,
		DocumentKind kind,
		String originalFilename,
		String contentType,
		long sizeBytes,
		Instant createdAt,
		UUID uploadedById,
		@Nullable String uploadedByName,
		UUID meetingId,
		@Nullable String meetingAgenda,
		@Nullable Instant meetingScheduledAt,
		Instant reviewedAt,
		UUID reviewedById,
		@Nullable String reviewedByName,
		List<DocumentShareResponse> shares) {
}
