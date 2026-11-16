package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An Ödev a coordinator gave every team of a program; each team holds its own copy as an {@link ActionItem}. */
@Entity
@Table(name = "program_assignment")
@Getter
@Setter
@NoArgsConstructor
public class ProgramAssignment {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(nullable = false)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "due_date")
	private LocalDate dueDate;

	@Column(name = "program_phase_id")
	private UUID programPhaseId;

	/** The team hands it in with a file; otherwise marking it done is enough. */
	@Column(name = "file_required", nullable = false)
	private boolean fileRequired;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by_id")
	private AppUser createdBy;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** Still open for a team that joins now: no due date, or due today or later. */
	public boolean openOn(LocalDate today) {
		return dueDate == null || !dueDate.isBefore(today);
	}
}
