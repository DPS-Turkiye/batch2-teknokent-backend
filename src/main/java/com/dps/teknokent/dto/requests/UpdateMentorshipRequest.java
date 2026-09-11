package com.dps.teknokent.dto.requests;

import java.time.LocalDate;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record UpdateMentorshipRequest(
		@NotNull LocalDate startDate,
		LocalDate endDate,
		@NotNull @Min(1) @Max(7) Integer daysPerWeek) {
}
