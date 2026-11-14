package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An "Ödev". {@code phaseId} names a phase of the team's program; without it the Ödev is placed by its due date
 * (or the day it was made). {@code stage} is the retired fixed stage list, still accepted for one release.
 */
public record CreateProcessAssignmentRequest(
		@NotNull UUID actorId,
		UUID roundId,
		StartupStage stage,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 10000) String description,
		LocalDate dueDate,
		UUID phaseId) {
}
