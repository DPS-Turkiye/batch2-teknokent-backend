package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MentorProgramRoleRequest(@NotNull UUID programId, @Size(max = 80) String role) {
}
