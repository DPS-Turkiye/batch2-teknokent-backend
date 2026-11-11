package com.dps.teknokent.dto.responses;

/** Whether the signed-in address may join a program; {@code message} says why not. */
public record ProgramEligibilityResponse(boolean eligible, String message) {

	public static ProgramEligibilityResponse allowed() {
		return new ProgramEligibilityResponse(true, null);
	}

	public static ProgramEligibilityResponse refused(String message) {
		return new ProgramEligibilityResponse(false, message);
	}
}
