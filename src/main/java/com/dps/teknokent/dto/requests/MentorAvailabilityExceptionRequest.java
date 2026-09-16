package com.dps.teknokent.dto.requests;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MentorAvailabilityExceptionRequest(
		@NotNull LocalDate startsOn,
		@NotNull LocalDate endsOn,
		@Size(max = 200) String reason) {
}
