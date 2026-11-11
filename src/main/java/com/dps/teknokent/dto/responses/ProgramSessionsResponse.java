package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Sessions the mentors confirmed as held in one program between {@code from} and {@code to} (both inclusive),
 * one row per mentor and one per team; in an individual program each team is one student.
 */
public record ProgramSessionsResponse(
		UUID programId,
		LocalDate from,
		LocalDate to,
		List<Row> mentors,
		List<Row> students) {

	/** {@code email} lists every member of a team, comma-separated. */
	public record Row(UUID id, String name, String email, int sessions, int minutes, int days) {
	}
}
