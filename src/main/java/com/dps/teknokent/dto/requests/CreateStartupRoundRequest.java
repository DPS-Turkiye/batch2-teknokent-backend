package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateStartupRoundRequest(
		@NotNull UUID actorId,
		@NotBlank @Size(max = 255) String title,
		@NotBlank @Size(max = 255) String name,
		@NotNull StartupStage stage,
		@NotNull LocalDate startsOn,
		@NotNull LocalDate endsOn) {
}
