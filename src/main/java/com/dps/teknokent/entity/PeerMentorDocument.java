package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.PeerDocumentVisibility;
import com.dps.teknokent.domain.PeerEvidenceType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A piece of evidence a peer mentor attached to their profile: a stored file, or a link for a portfolio. */
@Entity
@Table(name = "peer_mentor_document")
@Getter
@Setter
@NoArgsConstructor
public class PeerMentorDocument {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "evidence_type", nullable = false, length = 32)
	private PeerEvidenceType evidenceType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private PeerDocumentVisibility visibility;

	/** The stored file; null for a link. */
	@Column(name = "document_id", unique = true)
	private UUID documentId;

	/** The link; null for a file. */
	@Column(length = 500)
	private String url;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
