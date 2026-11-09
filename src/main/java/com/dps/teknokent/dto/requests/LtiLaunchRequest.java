package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;

/** What Moodle posted to the frontend's launch URL after the login step. */
public record LtiLaunchRequest(
		@NotBlank String idToken,
		@NotBlank String state) {
}
