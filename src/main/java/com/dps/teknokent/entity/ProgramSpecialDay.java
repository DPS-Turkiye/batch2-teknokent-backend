package com.dps.teknokent.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A day of a program's plan that is an event of its own rather than part of a week (a bootcamp, a rehearsal, a demo
 * day). Saved and replaced with the weekly plan.
 */
@Entity
@Table(name = "program_special_day")
@Getter
@Setter
@NoArgsConstructor
public class ProgramSpecialDay {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "event_date", nullable = false)
	private LocalDate date;

	@Column(name = "start_time")
	private LocalTime startTime;

	@Column(name = "end_time")
	private LocalTime endTime;

	@Column(length = 160)
	private String place;

	@Column(nullable = false, length = 160)
	private String title;

	@Column(nullable = false, columnDefinition = "TEXT")
	private String details = "";

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "updated_by")
	private UUID updatedBy;
}
