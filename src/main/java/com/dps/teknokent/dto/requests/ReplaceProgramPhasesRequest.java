package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The phases in order; the list replaces the program's current phases. A {@code syllabusDocumentId} also
 * replaces the program's syllabus file; null keeps the current one.
 */
public record ReplaceProgramPhasesRequest(
		UUID actorId,
		@NotNull @Size(max = 12) List<@Valid PhaseInput> phases,
		UUID syllabusDocumentId) {

	public record PhaseInput(
			@NotBlank @Size(max = 120) String name,
			LocalDate startDate,
			LocalDate endDate,
			@Size(max = 200) String expectedOutput) {
	}
}
