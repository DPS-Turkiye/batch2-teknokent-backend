package com.dps.teknokent.exception;

/**
 * A 400 for a team code that opens nothing for this caller: unknown, archived, the lead's own code, or another
 * program's startup. Its own type so join attempts can count these failures toward a guessing limit.
 */
public class InvalidTeamCodeException extends InvalidRequestException {

	public InvalidTeamCodeException(String message) {
		super(message);
	}
}
