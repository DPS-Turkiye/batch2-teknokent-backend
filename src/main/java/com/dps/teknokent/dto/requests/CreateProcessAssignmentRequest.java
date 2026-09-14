package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** An "Ödev": give a round, a stage, or both; the stage falls back to the round's. */
public record CreateProcessAssignmentRequest(
		@NotNull UUID actorId,
		UUID roundId,
		StartupStage stage,
		@NotBlank @Size(max = 255) String title,
		@Size(max = 10000) String description,
		LocalDate dueDate) {
}
