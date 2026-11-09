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

/** One graded quiz submission; kept for good, like a badge award. {@code answers}: "questionId=option,…". */
@Entity
@Table(name = "training_attempt")
@Getter
@Setter
@NoArgsConstructor
public class TrainingAttempt {

	@Id
	private UUID id;

	@Column(name = "module_id", nullable = false)
	private UUID moduleId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private int version;

	@Column(nullable = false)
	private int score;

	@Column(nullable = false)
	private boolean passed;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String answers;

	@Column(name = "submitted_at", nullable = false)
	private Instant submittedAt;
}
