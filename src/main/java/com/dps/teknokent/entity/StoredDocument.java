package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stored_document")
@Getter
@Setter
@NoArgsConstructor
public class StoredDocument {

	@Id
	private UUID id;

	@Column(name = "user_id")
	private UUID userId;

	@Column(name = "organization_id")
	private UUID organizationId;

	@Column(nullable = false, length = 32)
	private String kind;

	@Column(name = "original_filename", nullable = false)
	private String originalFilename;

	@Column(name = "content_type", nullable = false, length = 127)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private long sizeBytes;

	@Column(name = "s3_key", nullable = false, unique = true, length = 512)
	private String s3Key;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	// Set when the file belongs to a meeting: its attachments and note photos.
	@Column(name = "meeting_id")
	private UUID meetingId;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "reviewed_by_id")
	private UUID reviewedById;

	public void markReviewed(UUID reviewerId, Instant at) {
		this.reviewedById = reviewerId;
		this.reviewedAt = at;
	}
}
