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

/** A peer program's Akran Mentoru training: the current version's video; its questions are per version. */
@Entity
@Table(name = "training_module")
@Getter
@Setter
@NoArgsConstructor
public class TrainingModule {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false, unique = true)
	private UUID programId;

	@Column(name = "current_version", nullable = false)
	private int currentVersion;

	@Column(name = "video_id", nullable = false, length = 32)
	private String videoId;

	@Column(name = "published_at", nullable = false)
	private Instant publishedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "updated_by")
	private UUID updatedBy;
}
