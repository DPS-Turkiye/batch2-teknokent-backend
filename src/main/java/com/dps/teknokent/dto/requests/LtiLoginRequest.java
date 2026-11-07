package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;

/** The OIDC login initiation Moodle sent to the frontend, passed on as-is. */
public record LtiLoginRequest(
		@NotBlank String issuer,
		@NotBlank String loginHint,
		@NotBlank String targetLinkUri,
		String ltiMessageHint,
		String clientId,
		String deploymentId) {
}
