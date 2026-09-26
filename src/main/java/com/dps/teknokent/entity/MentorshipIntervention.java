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
@Table(name = "mentorship_intervention")
@Getter
@Setter
@NoArgsConstructor
public class MentorshipIntervention {

	@Id
	private UUID id;

	@Column(name = "mentorship_id", nullable = false)
	private UUID mentorshipId;

	@Column(name = "coordinator_id", nullable = false)
	private UUID coordinatorId;

	@Column(name = "opened_by", nullable = false)
	private UUID openedBy;

	@Column(name = "notification_id")
	private UUID notificationId;

	@Column(nullable = false, length = 160)
	private String action;

	@Column(length = 500)
	private String note;

	@Column(name = "opened_at", nullable = false)
	private Instant openedAt;

	@Column(name = "resolved_at")
	private Instant resolvedAt;
}
