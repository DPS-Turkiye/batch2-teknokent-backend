package com.dps.teknokent.util;

/** Regex constants for {@code @Pattern}, which needs a compile-time constant. */
public final class ValidationPatterns {

	/** {@code @Email} alone accepts "can@tohum"; a mail sent to an address without a dotted domain never arrives. */
	public static final String DOTTED_EMAIL = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

	private ValidationPatterns() {
	}
}
