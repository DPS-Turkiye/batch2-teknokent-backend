package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** A weekly program PDF the coordinator uploaded, to be read for the program in the path. */
public record ReadWeeklyProgramRequest(UUID actorId, @NotNull UUID documentId) {
}
