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
@Table(name = "program_startup")
@Getter
@Setter
@NoArgsConstructor
public class ProgramStartup {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
