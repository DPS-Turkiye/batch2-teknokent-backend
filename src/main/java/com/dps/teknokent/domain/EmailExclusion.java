package com.dps.teknokent.domain;

/** Why a person on the audience is not written to; shown to the sender in the review. */
public enum EmailExclusion {
	ACCOUNT_NOT_APPROVED,
	NO_ADDRESS,
	/** Listed by a founder, but not somebody to invite (an account uses the address). */
	NOT_ELIGIBLE,
	UNSUBSCRIBED,
	/** An earlier mail bounced or was reported as spam. */
	UNDELIVERABLE,
	/** The deployment only writes to its allowlist. */
	NOT_ON_ALLOWLIST
}
