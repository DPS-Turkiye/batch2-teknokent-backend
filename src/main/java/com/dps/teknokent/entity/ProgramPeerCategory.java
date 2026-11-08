package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One category a coordinator turned on for a peer-mentoring program. */
@Entity
@Table(name = "program_peer_category",
		uniqueConstraints = @UniqueConstraint(name = "uq_program_peer_category", columnNames = { "program_id", "category" }))
@Getter
@Setter
@NoArgsConstructor
public class ProgramPeerCategory {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private PeerTopicCategory category;

	@Column(name = "activated_by")
	private UUID activatedBy;

	@Column(name = "activated_at", nullable = false)
	private Instant activatedAt;

	public ProgramPeerCategory(UUID programId, PeerTopicCategory category, UUID activatedBy, Instant now) {
		this.id = UUID.randomUUID();
		this.programId = programId;
		this.category = category;
		this.activatedBy = activatedBy;
		this.activatedAt = now;
	}
}
