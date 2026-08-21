package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingStatus;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LogMeetingRequest(
		@NotNull UUID mentorId,
		@NotNull Instant scheduledAt,
		@NotNull @Min(1) Integer durationMinutes,
		@Size(max = 10000) String agenda,
		@Size(max = 10000) String notes,
		@Size(max = 10000) String outcomes,
		@NotNull MeetingStatus status) {
}
