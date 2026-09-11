package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.StartupStage;

import jakarta.validation.constraints.NotNull;

public record UpdateStartupStageRequest(
		@NotNull UUID actorId,
		@NotNull StartupStage stage) {
}
