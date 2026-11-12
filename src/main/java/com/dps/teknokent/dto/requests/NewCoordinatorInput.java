package com.dps.teknokent.dto.requests;

import static com.dps.teknokent.util.ValidationPatterns.DOTTED_EMAIL;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An email with no menton account, invited as a coordinator while the admin creates a program. */
public record NewCoordinatorInput(
		@NotBlank @Size(max = 255) String fullName,
		@NotBlank @Email @Pattern(regexp = DOTTED_EMAIL) @Size(max = 255) String email) {
}
