package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.StoredDocument;

public record StoredDocumentResponse(
		UUID id,
		UUID userId,
		UUID organizationId,
		String kind,
		String originalFilename,
		String contentType,
		long sizeBytes,
		Instant createdAt) {

	public static StoredDocumentResponse fromEntity(StoredDocument document) {
		return new StoredDocumentResponse(
				document.getId(),
				document.getUserId(),
				document.getOrganizationId(),
				document.getKind(),
				document.getOriginalFilename(),
				document.getContentType(),
				document.getSizeBytes(),
				document.getCreatedAt());
	}
}
