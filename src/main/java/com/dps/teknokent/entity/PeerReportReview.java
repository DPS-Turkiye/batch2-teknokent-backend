package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.PeerReportStatus;

import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/** The review life cycle every report level shares: the mentor sends, a program coordinator approves or sends back. */
@MappedSuperclass
@Getter
@Setter
public abstract class PeerReportReview {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 24)
	private PeerReportStatus status = PeerReportStatus.DRAFT;

	@Column(name = "submitted_at")
	private Instant submittedAt;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "reviewed_by")
	private UUID reviewedBy;

	@Column(name = "revision_note", columnDefinition = "TEXT")
	private String revisionNote;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Boxed so a new report (null) is persisted, not merged; Hibernate starts it at 0. */
	@Version
	@Column(nullable = false)
	private Long version;

	/** Sends the report for review; a revision note from an earlier round stays until the coordinator decides again. */
	public void submit(Instant at) {
		status = PeerReportStatus.IN_REVIEW;
		submittedAt = at;
		updatedAt = at;
	}
}
