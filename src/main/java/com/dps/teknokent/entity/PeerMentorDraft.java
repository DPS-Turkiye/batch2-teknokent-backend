package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A peer mentor's unfinished wizard (one per student), as the JSON of {@code PeerMentorProfileDraft}. */
@Entity
@Table(name = "peer_mentor_profile_draft")
@Getter
@Setter
@NoArgsConstructor
public class PeerMentorDraft {

	@Id
	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false)
	private String payload;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public PeerMentorDraft(UUID userId) {
		this.userId = userId;
	}
}
