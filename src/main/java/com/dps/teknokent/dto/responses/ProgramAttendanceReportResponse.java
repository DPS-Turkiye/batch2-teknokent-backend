package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Weekly attendance for the coordinator: the program weeks that overlap {@code from}–{@code to} and have begun,
 * and every participant with one flag per week, in the order of {@code weeks}.
 */
public record ProgramAttendanceReportResponse(
		UUID programId,
		LocalDate from,
		LocalDate to,
		List<Week> weeks,
		List<Participant> participants) {

	/** {@code rate} is the share of participants who checked in, 0–100; null without participants. */
	public record Week(int number, LocalDate start, LocalDate end, int checkedIn, int participants, Integer rate) {
	}

	public record Participant(String name, String email, String teamName, List<Boolean> attended, int attendedWeeks) {
	}
}
