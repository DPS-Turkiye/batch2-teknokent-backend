package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.domain.AttendanceSessionKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A participant's attendance at one session of a program week (its lesson or its workshop), or, for rows from before
 * per-session attendance, at the week as a whole. One row per participant and session.
 */
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

	@Enumerated(EnumType.STRING)
	@Column(name = "session_kind", nullable = false, length = 16)
	private AttendanceSessionKind sessionKind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private AttendanceMethod method;

	@Column(name = "checked_in_at", nullable = false)
	private Instant checkedInAt;

	/** The coordinator who marked a {@link AttendanceMethod#MANUAL} row; null otherwise. */
	@Column(name = "recorded_by")
	private UUID recordedBy;
}
