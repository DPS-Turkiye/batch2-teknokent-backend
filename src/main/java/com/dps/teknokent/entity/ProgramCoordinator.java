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
@Table(name = "program_coordinator")
@Getter
@Setter
@NoArgsConstructor
public class ProgramCoordinator {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "added_by", nullable = false)
	private UUID addedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
