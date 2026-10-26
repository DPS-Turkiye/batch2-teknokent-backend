package com.dps.teknokent.security;

/** Firebase could not be asked about a token, which says nothing about the token itself. */
public class FirebaseUnavailableException extends RuntimeException {

	public FirebaseUnavailableException(Throwable cause) {
		super("Firebase is unavailable", cause);
	}
}
