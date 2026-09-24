package com.dps.teknokent.entity;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(
		name = "program_phase",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_program_phase_position",
				columnNames = { "program_id", "position" }))
@Getter
@Setter
@NoArgsConstructor
public class ProgramPhase {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(nullable = false, length = 120)
	private String name;

	@Column(nullable = false)
	private int position;

	@Column(name = "start_date")
	private LocalDate startDate;

	@Column(name = "end_date")
	private LocalDate endDate;
}
