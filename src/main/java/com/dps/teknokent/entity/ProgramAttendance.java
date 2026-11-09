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

/** A participant's check-in for one week of a program; the week is 1-based from the program's start date. */
@Entity
@Table(name = "program_attendance")
@Getter
@Setter
@NoArgsConstructor
public class ProgramAttendance {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Column(name = "week_number", nullable = false)
	private int weekNumber;

	@Column(name = "checked_in_at", nullable = false)
	private Instant checkedInAt;
}
