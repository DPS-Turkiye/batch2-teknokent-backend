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

/** When a student's player reported the video of a version as watched (the end, or 90 %). */
@Entity
@Table(name = "training_progress")
@Getter
@Setter
@NoArgsConstructor
public class TrainingProgress {

	@Id
	private UUID id;

	@Column(name = "module_id", nullable = false)
	private UUID moduleId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(nullable = false)
	private int version;

	@Column(name = "video_completed_at", nullable = false)
	private Instant videoCompletedAt;
}
