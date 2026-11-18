package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** {@code token} is the one in the scanned QR code's link. */
public record SessionCheckInRequest(
		UUID actorId,
		@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9_-]+") String token) {
}
