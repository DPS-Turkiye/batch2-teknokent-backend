package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddCoordinatorRequest(
		UUID actorId,
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Email @Size(max = 255) String email) {
}
