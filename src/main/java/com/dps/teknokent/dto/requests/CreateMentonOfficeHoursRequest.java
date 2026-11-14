package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A Menton student's request: one of the academician's open slots, a short title and what they want to talk about.
 *
 * @param academicianId the academician's office-hours id from the list
 */
public record CreateMentonOfficeHoursRequest(
		@NotNull UUID academicianId,
		@NotNull Instant startsAt,
		@NotBlank @Size(max = 200) String title,
		@Size(max = 2000) String description) {
}
