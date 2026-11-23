package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.EmailAudience;
import com.dps.teknokent.domain.EmailCampaignStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One mail written in the email center to the people of a program. */
@Entity
@Table(name = "email_campaign")
@Getter
@Setter
@NoArgsConstructor
public class EmailCampaign {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private EmailAudience audience;

	@Column(nullable = false, length = 200)
	private String subject;

	/** Already sanitized; see {@code EmailHtml}. */
	@Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
	private String bodyHtml;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private EmailCampaignStatus status = EmailCampaignStatus.DRAFT;

	/** When the current text was last sent to its author; null again once the text changes. */
	@Column(name = "test_sent_at")
	private Instant testSentAt;

	/** Fingerprint of the text and the list the sender last reviewed. */
	@Column(name = "review_digest", length = 64)
	private String reviewDigest;

	@Column(name = "reviewed_at")
	private Instant reviewedAt;

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@Column(name = "finished_at")
	private Instant finishedAt;

	@Column(name = "alerted_problems", nullable = false)
	private int alertedProblems;

	@Column(name = "last_alert_at")
	private Instant lastAlertAt;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
