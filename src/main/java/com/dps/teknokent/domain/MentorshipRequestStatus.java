package com.dps.teknokent.domain;

public enum MentorshipRequestStatus {
	PENDING,
	MORE_INFO_REQUESTED,
	COUNTER_PROPOSED,
	ACCEPTED,
	DECLINED,
	WITHDRAWN;

	/** Still waiting for an answer from either side: nothing has begun, and it can still be withdrawn. */
	public boolean isOpen() {
		return this == PENDING || this == MORE_INFO_REQUESTED || this == COUNTER_PROPOSED;
	}
}
