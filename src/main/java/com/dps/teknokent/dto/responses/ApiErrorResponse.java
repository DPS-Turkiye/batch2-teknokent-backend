package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code code} is a stable, machine-readable reason a client may branch on; most errors have none and omit it. */
public record ApiErrorResponse(
		Instant timestamp,
		int status,
		String error,
		@Nullable String message,
		String path,
		Map<String, String> fieldErrors,
		@JsonInclude(JsonInclude.Include.NON_NULL) @Nullable String code) {

	public ApiErrorResponse(
			Instant timestamp, int status, String error, @Nullable String message, String path,
			Map<String, String> fieldErrors) {
		this(timestamp, status, error, message, path, fieldErrors, null);
	}
}
