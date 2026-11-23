package com.dps.teknokent.domain;

/** Why the email center no longer writes to an address. */
public enum EmailSuppressionReason {
	UNSUBSCRIBED,
	HARD_BOUNCE,
	COMPLAINT
}
