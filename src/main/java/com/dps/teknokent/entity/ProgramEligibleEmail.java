package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One address on a program's list of people allowed to join; stored lowercase. */
@Entity
@Table(name = "program_eligible_email",
		uniqueConstraints = @UniqueConstraint(name = "uq_program_eligible_email", columnNames = { "program_id", "email" }))
@Getter
@Setter
@NoArgsConstructor
public class ProgramEligibleEmail {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(nullable = false, length = 320)
	private String email;

	@Column(name = "added_by")
	private UUID addedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
