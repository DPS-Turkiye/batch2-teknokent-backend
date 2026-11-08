package com.dps.teknokent.dto.responses;

/** A single-use code, good for 60 seconds, that the frontend trades for a Firebase custom token. */
public record InstitutionLoginCodeResponse(String code) {
}
