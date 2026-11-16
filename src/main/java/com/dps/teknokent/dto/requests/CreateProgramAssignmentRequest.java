package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Ödev ver": one Ödev for every team of the program. {@code phaseId} names a phase of the program; without it each
 * team's copy is placed by its due date. With {@code fileRequired} a team hands it in only with a file.
 */
public record CreateProgramAssignmentRequest(
		UUID actorId,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 10000) String description,
		LocalDate dueDate,
		UUID phaseId,
		boolean fileRequired) {
}
