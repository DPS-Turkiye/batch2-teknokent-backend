package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code @Email} alone accepts "can@tohum"; a code sent to an address without a domain never arrives. */
public record TeamInviteInput(
		@NotBlank @Size(max = 255) String teamName,
		@NotBlank @Email @Pattern(regexp = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") @Size(max = 255) String leadEmail) {
}
