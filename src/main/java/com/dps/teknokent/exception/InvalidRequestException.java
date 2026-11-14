package com.dps.teknokent.exception;

import org.jspecify.annotations.Nullable;

/** A 400. {@code code}, when set, is a stable reason the client may branch on (see {@code ApiErrorResponse}). */
public class InvalidRequestException extends RuntimeException {

	private final @Nullable String code;

	public InvalidRequestException(String message) {
		this(message, null);
	}

	public InvalidRequestException(String message, @Nullable String code) {
		super(message);
		this.code = code;
	}

	public @Nullable String code() {
		return code;
	}
}
