package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "meeting_assistant_share")
@Getter
@Setter
@NoArgsConstructor
public class MeetingAssistantShare {

	public static MeetingAssistantShare emptyFor(Meeting meeting) {
		MeetingAssistantShare share = new MeetingAssistantShare();
		share.id = UUID.randomUUID();
		share.meeting = meeting;
		return share;
	}

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "meeting_id", nullable = false, unique = true)
	private Meeting meeting;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "published_by_id")
	private AppUser publishedBy;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column
	private String transcript;

	@Column(name = "transcript_s3_key", length = 1024)
	private String transcriptS3Key;

	@Column(name = "transcript_published_at")
	private Instant transcriptPublishedAt;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column
	private String report;

	@Column(name = "report_s3_key", length = 1024)
	private String reportS3Key;

	@Column(name = "report_published_at")
	private Instant reportPublishedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
