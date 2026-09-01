package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateProgramRequest(
		@NotBlank @Size(max = 255) String name,
		UUID actorId) {
}
