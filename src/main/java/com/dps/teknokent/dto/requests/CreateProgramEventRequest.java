package com.dps.teknokent.dto.requests;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.ProgramEventKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProgramEventRequest(
		UUID actorId,
		@NotBlank @Size(max = 160) String title,
		@NotNull ProgramEventKind kind,
		@NotNull Instant startsAt,
		Instant endsAt,
		@Size(max = 2000) String description) {
}
