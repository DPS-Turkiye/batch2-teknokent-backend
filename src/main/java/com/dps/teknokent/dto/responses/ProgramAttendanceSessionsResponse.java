package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceSessionKind;

/** Every session of a program's weeks that takes attendance, for the coordinator's QR codes. */
public record ProgramAttendanceSessionsResponse(
		UUID programId,
		String programName,
		boolean attendanceEnabled,
		List<Session> sessions) {

	/** {@code hasQr} once a QR code was shown for the session; {@code checkedIn} counts its attendances. */
	public record Session(int week, AttendanceSessionKind kind, LocalDate date, @Nullable LocalTime startTime,
			@Nullable LocalTime endTime, @Nullable String title, boolean hasQr, int checkedIn) {
	}
}
