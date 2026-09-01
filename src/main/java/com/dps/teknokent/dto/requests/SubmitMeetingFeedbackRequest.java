package com.dps.teknokent.dto.requests;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitMeetingFeedbackRequest(
		@NotEmpty @Size(max = 20) List<@NotNull @Min(1) @Max(5) Integer> ratings,
		@NotNull @Size(max = 20) List<@Size(max = 255) String> benefits) {
}
