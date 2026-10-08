package com.dps.teknokent.dto.requests;

import static com.dps.teknokent.util.ValidationPatterns.DOTTED_EMAIL;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TeamInviteInput(
		@NotBlank @Size(max = 255) String teamName,
		@NotBlank @Email @Pattern(regexp = DOTTED_EMAIL) @Size(max = 255) String leadEmail) {
}
