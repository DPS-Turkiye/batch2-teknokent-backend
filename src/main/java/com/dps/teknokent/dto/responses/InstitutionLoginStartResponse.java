package com.dps.teknokent.dto.responses;

/**
 * Where to send the browser to sign in at the institution ({@code redirectUrl}; a path on the Menton frontend when it
 * starts with "/"), and the single-use {@code state} the callback must bring back.
 */
public record InstitutionLoginStartResponse(String redirectUrl, String state) {
}
