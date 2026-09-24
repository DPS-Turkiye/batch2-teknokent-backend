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

/** A program coordinator's thread with one team or one mentor of that program. */
@Entity
@Table(name = "coordinator_conversation")
@Getter
@Setter
@NoArgsConstructor
public class CoordinatorConversation {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "organization_id")
	private UUID organizationId;

	@Column(name = "mentor_id")
	private UUID mentorId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "last_message_at")
	private Instant lastMessageAt;

	@Column(name = "last_message_preview", length = 200)
	private String lastMessagePreview;

	@Column(name = "coordinator_read_at")
	private Instant coordinatorReadAt;

	@Column(name = "counterpart_read_at")
	private Instant counterpartReadAt;
}
