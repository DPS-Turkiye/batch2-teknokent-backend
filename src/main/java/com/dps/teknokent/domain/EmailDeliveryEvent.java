package com.dps.teknokent.domain;

/** What SES reports about a mail after accepting it. */
public enum EmailDeliveryEvent {
	DELIVERED,
	/** The address will never work. */
	HARD_BOUNCE,
	/** A full mailbox or the like: this mail failed, the next may not. */
	SOFT_BOUNCE,
	COMPLAINT,
	/** SES itself refused the mail after accepting it. */
	REJECTED
}
