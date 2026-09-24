package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendCoordinatorMessageRequest(
		UUID actorId,
		@NotBlank @Size(max = 4000) String body) {
}
