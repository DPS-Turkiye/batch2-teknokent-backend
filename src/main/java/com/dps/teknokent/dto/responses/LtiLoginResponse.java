package com.dps.teknokent.dto.responses;

/** Where the frontend sends the browser next: Moodle's authorization endpoint with this launch's state and nonce. */
public record LtiLoginResponse(String redirectUrl) {
}
