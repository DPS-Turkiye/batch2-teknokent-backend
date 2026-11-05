package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** The program's dates let week numbers in the syllabus become dates. */
public record ExtractSyllabusPhasesRequest(
		UUID actorId,
		@NotNull UUID documentId,
		LocalDate startDate,
		LocalDate endDate) {
}
