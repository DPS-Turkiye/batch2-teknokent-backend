package com.dps.teknokent.dto.responses;

/** A Firebase custom token; the browser signs in with it and continues like any other Firebase user. */
public record InstitutionCustomTokenResponse(String customToken) {
}
