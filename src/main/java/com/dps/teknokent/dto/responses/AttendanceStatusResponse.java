package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.AttendanceState;

/**
 * The caller's check-in for the program week running today. {@code week}, {@code weekStart} and {@code weekEnd}
 * are null when no week is running; {@code checkedInAt} is set once the caller has checked in.
 */
public record AttendanceStatusResponse(
		UUID programId,
		String programName,
		AttendanceState state,
		Integer week,
		Integer totalWeeks,
		LocalDate weekStart,
		LocalDate weekEnd,
		Instant checkedInAt) {
}
