package com.dps.teknokent.dto.requests;

import java.time.DayOfWeek;
import java.time.LocalTime;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateMentorAvailabilityRuleRequest(
		@NotNull DayOfWeek dayOfWeek,
		@NotNull LocalTime startTime,
		@NotNull LocalTime endTime,
		@Min(15) @Max(480) Integer slotMinutes) {
}
