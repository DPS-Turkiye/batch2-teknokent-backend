package com.dps.teknokent.domain;

import java.util.EnumSet;
import java.util.Set;

/** What became of one recipient of an email-center mail. */
public enum EmailRecipientStatus {
	/** On the audience but not written to; see {@link EmailExclusion}. */
	EXCLUDED,
	/** On the list the sender is reviewing. */
	REVIEWED,
	/** Taken off by the sender. */
	UNTICKED,
	PENDING,
	SENDING,
	SENT,
	DELIVERED,
	BOUNCED,
	COMPLAINED,
	FAILED,
	/** Confirmed, but unsubscribed, deleted or undeliverable by the time of sending. */
	SKIPPED;

	/** What the sender and the admins are alerted about. */
	public static final Set<EmailRecipientStatus> PROBLEMS = Set.copyOf(EnumSet.of(BOUNCED, COMPLAINED, FAILED));
}
