package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreateProgramMatchRequest(UUID actorId, @NotNull UUID organizationId, @NotNull UUID mentorId) {
}
