package com.dps.teknokent.util;

/** Regex constants for {@code @Pattern}, which needs a compile-time constant. */
public final class ValidationPatterns {

	/** {@code @Email} alone accepts "can@tohum"; a mail sent to an address without a dotted domain never arrives. */
	public static final String DOTTED_EMAIL = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";

	/**
	 * A link typed into a profile: blank, an http(s) address, or one with no scheme at all ("ornek.com", as the
	 * forms' placeholders suggest). Any other scheme ({@code javascript:}, {@code data:}) is refused, so a stored
	 * link can never run as script where it is shown.
	 */
	public static final String WEB_LINK = "(?i)^\\s*(|https?://\\S+|[^\\s:/?#]+([/?#]\\S*)?)\\s*$";

	public static final String WEB_LINK_MESSAGE = "must be a web address (http or https)";

	private ValidationPatterns() {
	}
}
