package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** The program the team leaves. Named by the admin, so a team listed in two programs is moved out of the right one. */
public record MoveStartupProgramRequest(@NotNull UUID fromProgramId) {
}
