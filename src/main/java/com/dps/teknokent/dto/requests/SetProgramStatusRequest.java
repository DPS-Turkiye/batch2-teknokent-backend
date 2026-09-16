package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.ProgramStatus;

import jakarta.validation.constraints.NotNull;

public record SetProgramStatusRequest(UUID actorId, @NotNull ProgramStatus status) {
}
