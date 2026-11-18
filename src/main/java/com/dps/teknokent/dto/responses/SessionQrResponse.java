package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceSessionKind;

/**
 * A session's QR code for the coordinator to show or print: the token its link carries, and when the link accepts
 * check-ins ({@code opensAt} inclusive, {@code closesAt} exclusive).
 */
public record SessionQrResponse(
		UUID programId,
		String programName,
		int week,
		AttendanceSessionKind kind,
		LocalDate date,
		@Nullable LocalTime startTime,
		@Nullable LocalTime endTime,
		@Nullable String title,
		String token,
		Instant opensAt,
		Instant closesAt) {
}
