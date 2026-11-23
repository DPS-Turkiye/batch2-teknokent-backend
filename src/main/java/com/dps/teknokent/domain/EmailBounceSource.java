package com.dps.teknokent.domain;

import java.util.Locale;

/** Where the email center learns that a mail bounced or was reported as spam. */
public enum EmailBounceSource {
	NONE,
	/** Asks SES for the addresses it stopped delivering to. */
	SUPPRESSION_LIST,
	/** Reads the events a configuration set publishes. */
	SES_EVENTS;

	/** An unknown value stops the startup rather than leave a sender unaware of bounces. */
	public static EmailBounceSource parse(String value) {
		return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
	}
}
