package com.dps.teknokent.dto.requests;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

public record UpsertMentorshipWeekCheckRequest(
		@NotNull LocalDate weekStart,
		@NotNull Boolean completed) {
}
