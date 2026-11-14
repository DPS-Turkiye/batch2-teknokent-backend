package com.dps.teknokent.exception;

import org.jspecify.annotations.Nullable;

/** A 409. {@code code}, when set, is a stable reason the client may branch on (see {@code ApiErrorResponse}). */
public class ConflictException extends RuntimeException {

	private final @Nullable String code;

	public ConflictException(String message) {
		this(message, null);
	}

	public ConflictException(String message, @Nullable String code) {
		super(message);
		this.code = code;
	}

	public @Nullable String code() {
		return code;
	}
}
