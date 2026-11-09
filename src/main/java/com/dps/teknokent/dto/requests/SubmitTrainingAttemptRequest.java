package com.dps.teknokent.dto.requests;

import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** {@code answers}: question id → the chosen option's index (0–3). */
public record SubmitTrainingAttemptRequest(UUID actorId, int version, @NotNull Map<UUID, Integer> answers) {
}
