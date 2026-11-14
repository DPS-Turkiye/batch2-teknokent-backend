package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceState;

/**
 * The caller's check-in for the program week running today. {@code week}, {@code weekStart} and {@code weekEnd}
 * are null when no week is running; {@code checkedInAt} is set once the caller has checked in.
 */
public record AttendanceStatusResponse(
		@Nullable UUID programId,
		@Nullable String programName,
		AttendanceState state,
		@Nullable Integer week,
		@Nullable Integer totalWeeks,
		@Nullable LocalDate weekStart,
		@Nullable LocalDate weekEnd,
		@Nullable Instant checkedInAt) {
}
