package com.dps.teknokent.exception;

/** A 400. {@code code}, when set, is a stable reason the client may branch on (see {@code ApiErrorResponse}). */
public class InvalidRequestException extends RuntimeException {

	private final String code;

	public InvalidRequestException(String message) {
		this(message, null);
	}

	public InvalidRequestException(String message, String code) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
