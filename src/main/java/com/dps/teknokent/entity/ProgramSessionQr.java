package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.AttendanceSessionKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The QR code of one session (a week's lesson or workshop): the token its link carries. The session's day and time
 * are not stored here; they come from the program's current plan, so reading the plan again keeps the code.
 */
@Entity
@Table(name = "program_session_qr", uniqueConstraints = {
		@UniqueConstraint(name = "uq_program_session_qr", columnNames = { "program_id", "week_number",
				"session_kind" }),
		@UniqueConstraint(name = "uq_program_session_qr_token", columnNames = "token") })
@Getter
@Setter
@NoArgsConstructor
public class ProgramSessionQr {

	@Id
	private UUID id;

	@Column(name = "program_id", nullable = false)
	private UUID programId;

	@Column(name = "week_number", nullable = false)
	private int weekNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "session_kind", nullable = false, length = 16)
	private AttendanceSessionKind sessionKind;

	@Column(nullable = false, length = 64)
	private String token;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "created_by")
	private UUID createdBy;
}
