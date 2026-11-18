package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceMethod;
import com.dps.teknokent.domain.AttendanceSessionKind;

/**
 * Attendance for the coordinator, per session: the lessons and workshops of the program weeks that overlap
 * {@code from}–{@code to} and have begun, held by today, and every participant with one mark per session, in the
 * order of {@code sessions}. A week with weekly self-check-ins from before per-session attendance also has a
 * session of kind WEEK.
 */
public record ProgramAttendanceReportResponse(
		UUID programId,
		LocalDate from,
		LocalDate to,
		boolean attendanceEnabled,
		List<Session> sessions,
		List<Participant> participants) {

	/**
	 * {@code date} is null for a WEEK session, and for a session the current plan no longer has but someone attended.
	 * {@code rate} is the share of participants who attended, 0–100; null without participants.
	 */
	public record Session(int week, AttendanceSessionKind kind, @Nullable LocalDate date,
			@Nullable LocalTime startTime, @Nullable LocalTime endTime, @Nullable String title, int checkedIn,
			int participants, @Nullable Integer rate) {
	}

	/** {@code current} is false for someone who attended here earlier but has since left the program. */
	public record Participant(UUID userId, String name, String email, @Nullable String teamName, boolean current,
			List<Mark> marks, int attendedSessions) {
	}

	/** {@code method} says how an attendance was recorded; null when the participant did not attend. */
	public record Mark(boolean attended, @Nullable AttendanceMethod method) {
	}
}
