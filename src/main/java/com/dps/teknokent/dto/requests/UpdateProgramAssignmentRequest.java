package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * "Düzenle" on a program Ödev. All three fields are sent on every save, so a missing {@code dueDate} clears the due
 * date rather than leaving it as it was.
 */
public record UpdateProgramAssignmentRequest(
		UUID actorId,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 10000) String description,
		LocalDate dueDate) {
}
