package com.dps.teknokent.domain;

/** Why a confirmed mail did not go to one of its people after all; shown to the sender. */
public enum EmailSkipReason {
	/** The sender or an admin stopped the mail. */
	STOPPED,
	/** It waited too long, for example while sending was switched off. */
	EXPIRED,
	NO_ADDRESS,
	SENDER_NOT_ALLOWED,
	ACCOUNT_NOT_APPROVED,
	ADDRESS_CHANGED,
	LEFT_THE_PROGRAM,
	SUPPRESSED,
	NOT_ON_ALLOWLIST
}
