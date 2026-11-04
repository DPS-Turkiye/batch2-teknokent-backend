package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.dps.teknokent.domain.TeamChangeKind;
import com.dps.teknokent.domain.TeamChangeStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A member's wish to leave, or the leader's wish to remove a member, waiting for the program's coordinator. */
@Entity
@Table(name = "team_change_request")
@Getter
@Setter
@NoArgsConstructor
public class TeamChangeRequest {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "organization_id", nullable = false)
	private Organization organization;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "member_id", nullable = false)
	private AppUser member;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "requested_by_id", nullable = false)
	private AppUser requestedBy;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private TeamChangeKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private TeamChangeStatus status = TeamChangeStatus.PENDING;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "decided_by_id")
	private AppUser decidedBy;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "decided_at")
	private Instant decidedAt;
}
