package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One label on one mentor, in the label's program. */
@Entity
@Table(name = "program_mentor_label_assignment", uniqueConstraints = @UniqueConstraint(name = "uq_program_mentor_label_assignment", columnNames = {
		"label_id", "mentor_id" }))
@Getter
@Setter
@NoArgsConstructor
public class ProgramMentorLabelAssignment {

	@Id
	private UUID id;

	@Column(name = "label_id", nullable = false)
	private UUID labelId;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "mentor_id", nullable = false)
	private UUID mentorId;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
