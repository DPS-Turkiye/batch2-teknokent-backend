package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.dps.teknokent.domain.MeetingProposalStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "meeting_proposal")
@Getter
@Setter
@NoArgsConstructor
public class MeetingProposal {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentorship_id", nullable = false)
	private Mentorship mentorship;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "proposed_by", nullable = false)
	private UUID proposedBy;

	@Column(name = "scheduled_at", nullable = false)
	private Instant scheduledAt;

	@Column(name = "duration_minutes", nullable = false)
	private int durationMinutes;

	@Column(length = 500)
	private String agenda;

	@Column(name = "replaces_meeting_id")
	private UUID replacesMeetingId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private MeetingProposalStatus status;

	@Column(name = "mentor_accepted_at")
	private Instant mentorAcceptedAt;

	@Column(name = "team_accepted_at")
	private Instant teamAcceptedAt;

	@Column(name = "team_responder_id")
	private UUID teamResponderId;

	@Column(name = "declined_by")
	private UUID declinedBy;

	@Column(name = "decline_reason", length = 500)
	private String declineReason;

	@Column(name = "meeting_id")
	private UUID meetingId;

	@Column(name = "reminded_at")
	private Instant remindedAt;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	public boolean isPending() {
		return status == MeetingProposalStatus.PENDING;
	}
}
