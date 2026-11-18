package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceSessionKind;

/** The session a scanned QR code checked the caller in to; {@code alreadyRecorded} when an earlier scan had. */
public record SessionCheckInResponse(
		UUID programId,
		String programName,
		int week,
		AttendanceSessionKind kind,
		LocalDate date,
		@Nullable LocalTime startTime,
		@Nullable LocalTime endTime,
		@Nullable String title,
		Instant checkedInAt,
		boolean alreadyRecorded) {
}
