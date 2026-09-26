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
@Table(name = "program_mentor_invite")
@Getter
@Setter
@NoArgsConstructor
public class ProgramMentorInvite {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false, length = 64)
	private String code;

	@Column(name = "expires_at", nullable = false)
	private Instant expiresAt;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "mentor_id")
	private UUID mentorId;

	@Column(name = "joined_at")
	private Instant joinedAt;
}
