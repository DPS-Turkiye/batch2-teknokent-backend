package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coordinator_note")
@Getter
@Setter
@NoArgsConstructor
public class CoordinatorNote {

	@Id
	private UUID id;

	@Column(name = "mentorship_id", nullable = false)
	private UUID mentorshipId;

	@Column(name = "author_id", nullable = false)
	private UUID authorId;

	@Column(nullable = false, length = 2000)
	private String body;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;
}
