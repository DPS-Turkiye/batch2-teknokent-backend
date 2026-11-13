package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.PeerDocumentVisibility;
import com.dps.teknokent.domain.PeerEvidenceType;
import com.dps.teknokent.entity.PeerMentorDocument;
import com.dps.teknokent.entity.StoredDocument;

/**
 * A peer mentor's evidence as the wizard and the profile list it: a link has {@code url}, a file its name, type and
 * size (the file itself is read through its own path).
 */
public record PeerMentorDocumentResponse(
		UUID id,
		PeerEvidenceType evidenceType,
		PeerDocumentVisibility visibility,
		String url,
		String originalFilename,
		String contentType,
		Long sizeBytes,
		Instant createdAt) {

	public static PeerMentorDocumentResponse ofLink(PeerMentorDocument row) {
		return new PeerMentorDocumentResponse(row.getId(), row.getEvidenceType(), row.getVisibility(), row.getUrl(),
				null, null, null, row.getCreatedAt());
	}

	public static PeerMentorDocumentResponse ofFile(PeerMentorDocument row, StoredDocument file) {
		return new PeerMentorDocumentResponse(row.getId(), row.getEvidenceType(), row.getVisibility(), null,
				file.getOriginalFilename(), file.getContentType(), file.getSizeBytes(), row.getCreatedAt());
	}
}
