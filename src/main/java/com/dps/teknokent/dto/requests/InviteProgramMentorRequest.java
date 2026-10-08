package com.dps.teknokent.dto.requests;

import static com.dps.teknokent.util.ValidationPatterns.DOTTED_EMAIL;

import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InviteProgramMentorRequest(
		UUID actorId,
		@NotBlank @Email @Pattern(regexp = DOTTED_EMAIL) @Size(max = 255) String email) {
}
