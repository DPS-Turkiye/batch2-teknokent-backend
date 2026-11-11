package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A student's office-hours request: what about, and up to three times ("yyyy-MM-ddTHH:mm", Turkey time). */
public record CreateOfficeHoursRequest(
		@NotNull UUID instructorId,
		@NotBlank @Size(max = 2000) String topic,
		@Size(max = 2000) String expectedOutcome,
		@NotEmpty @Size(max = 3) List<@Pattern(regexp = SlotFormat.PATTERN) String> preferredSlots) {
}
