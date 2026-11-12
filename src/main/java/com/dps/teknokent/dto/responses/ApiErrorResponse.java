package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code code} is a stable, machine-readable reason a client may branch on; most errors have none and omit it. */
public record ApiErrorResponse(
		Instant timestamp,
		int status,
		String error,
		String message,
		String path,
		Map<String, String> fieldErrors,
		@JsonInclude(JsonInclude.Include.NON_NULL) String code) {

	public ApiErrorResponse(
			Instant timestamp, int status, String error, String message, String path, Map<String, String> fieldErrors) {
		this(timestamp, status, error, message, path, fieldErrors, null);
	}
}
