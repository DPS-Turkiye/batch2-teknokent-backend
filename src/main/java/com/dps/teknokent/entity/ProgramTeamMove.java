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
 * An admin moved a team from one program to another. The old {@link ProgramStartup} row is gone, so this is both
 * the audit of the move and the record that the team used to be in the old program. Ids only, by design: no name
 * or other personal data may be added here, and there are no foreign keys, so a row outlives what it names.
 */
@Entity
@Table(name = "program_team_move")
@Getter
@NoArgsConstructor
public class ProgramTeamMove {

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private UUID organizationId;

	@Column(name = "from_program_id", nullable = false, updatable = false)
	private UUID fromProgramId;

	@Column(name = "to_program_id", nullable = false, updatable = false)
	private UUID toProgramId;

	@Column(name = "moved_by_id", nullable = false, updatable = false)
	private UUID movedById;

	@Column(name = "moved_at", nullable = false, updatable = false)
	private Instant movedAt;

	/** When the team had joined the program it left. */
	@Column(name = "joined_from_at", nullable = false, updatable = false)
	private Instant joinedFromAt;

	public ProgramTeamMove(UUID organizationId, UUID fromProgramId, UUID toProgramId, UUID movedById,
			Instant movedAt, Instant joinedFromAt) {
		this.id = UUID.randomUUID();
		this.organizationId = organizationId;
		this.fromProgramId = fromProgramId;
		this.toProgramId = toProgramId;
		this.movedById = movedById;
		this.movedAt = movedAt;
		this.joinedFromAt = joinedFromAt;
	}
}
