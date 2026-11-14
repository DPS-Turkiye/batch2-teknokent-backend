package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.PeerActivityType;

public record PeerActivityResponse(
		UUID id,
		LocalDate activityDate,
		PeerActivityType type,
		String title,
		int durationMinutes,
		UUID menteeId,
		@Nullable String menteeName,
		String description,
		Instant createdAt) {
}
