package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record DocumentRequestUploadResponse(
		UUID id,
		UUID storedDocumentId,
		String originalFilename,
		String contentType,
		long sizeBytes,
		UUID uploadedById,
		String uploadedByName,
		Instant createdAt) {
}
