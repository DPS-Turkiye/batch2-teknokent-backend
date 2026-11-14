package com.dps.teknokent.exception;

/**
 * A service behind the meeting assistant (the recording bots' API, or the AI writing a meeting report) failed or
 * could not be reached. Answered 502 with code {@link #CODE} and a fixed text: the service's own answer names
 * internal hosts, so it stays in the log.
 */
public class MeetingProviderException extends RuntimeException {

	public static final String CODE = "MEETING_PROVIDER_UNAVAILABLE";

	public MeetingProviderException(String message) {
		super(message);
	}

	public MeetingProviderException(String message, Throwable cause) {
		super(message, cause);
	}
}
