package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An admin took a mentor out of a program. The {@link ProgramMentor} row is gone, so this is both the audit of
 * the removal and the record that the mentor used to be in the program. Ids only, by design: no name or other
 * personal data may be added here, and there are no foreign keys, so a row outlives what it names.
 */
@Entity
@Table(name = "program_mentor_removal")
@Getter
@NoArgsConstructor
public class ProgramMentorRemoval {

	@Id
	private UUID id;

	@Column(name = "mentor_id", nullable = false, updatable = false)
	private UUID mentorId;

	@Column(name = "program_id", nullable = false, updatable = false)
	private UUID programId;

	@Column(name = "removed_by_id", nullable = false, updatable = false)
	private UUID removedById;

	@Column(name = "removed_at", nullable = false, updatable = false)
	private Instant removedAt;

	/** When the mentor had been put on the program's list (invited, asked to join or added). */
	@Column(name = "joined_at", nullable = false, updatable = false)
	private Instant joinedAt;

	public ProgramMentorRemoval(UUID mentorId, UUID programId, UUID removedById, Instant removedAt,
			Instant joinedAt) {
		this.id = UUID.randomUUID();
		this.mentorId = mentorId;
		this.programId = programId;
		this.removedById = removedById;
		this.removedAt = removedAt;
		this.joinedAt = joinedAt;
	}
}
