package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.domain.AttendanceState;

/**
 * The caller's attendance in the program week running today: the week's sessions that take attendance, each with
 * when the caller checked in to it. {@code week}, {@code weekStart} and {@code weekEnd} are null and
 * {@code sessions} is empty unless {@code state} is OPEN.
 */
public record AttendanceStatusResponse(
		@Nullable UUID programId,
		@Nullable String programName,
		AttendanceState state,
		@Nullable Integer week,
		@Nullable Integer totalWeeks,
		@Nullable LocalDate weekStart,
		@Nullable LocalDate weekEnd,
		List<Session> sessions) {

	public record Session(AttendanceSessionKind kind, LocalDate date, @Nullable LocalTime startTime,
			@Nullable LocalTime endTime, @Nullable String title, @Nullable Instant checkedInAt) {
	}
}
