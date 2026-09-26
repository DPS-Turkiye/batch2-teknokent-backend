package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InviteProgramMentorRequest(
		UUID actorId,
		@NotBlank @Email @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") @Size(max = 255) String email) {
}
