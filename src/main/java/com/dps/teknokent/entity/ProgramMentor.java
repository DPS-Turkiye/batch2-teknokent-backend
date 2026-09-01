package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramMentorInvitedBy;
import com.dps.teknokent.domain.ProgramMentorStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "program_mentor")
@Getter
@Setter
@NoArgsConstructor
public class ProgramMentor {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "mentor_id", nullable = false)
	private UUID mentorId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private ProgramMentorStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "invited_by", nullable = false, length = 32)
	private ProgramMentorInvitedBy invitedBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "decided_at")
	private Instant decidedAt;
}
