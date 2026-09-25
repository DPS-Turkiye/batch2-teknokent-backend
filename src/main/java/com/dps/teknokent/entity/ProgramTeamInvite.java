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
@Table(name = "program_team_invite")
@Getter
@Setter
@NoArgsConstructor
public class ProgramTeamInvite {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "team_name", nullable = false)
	private String teamName;

	@Column(name = "lead_email", nullable = false)
	private String leadEmail;

	@Column(nullable = false, length = 32)
	private String code;

	@Column(name = "organization_id")
	private UUID organizationId;

	@Column(name = "created_by", nullable = false)
	private UUID createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "joined_at")
	private Instant joinedAt;
}
