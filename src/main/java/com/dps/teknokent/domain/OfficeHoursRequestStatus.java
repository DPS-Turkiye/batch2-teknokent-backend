package com.dps.teknokent.domain;

/** The same steps a mentorship request goes through. */
public enum OfficeHoursRequestStatus {
	PENDING,
	ACCEPTED,
	DECLINED,
	MORE_INFO_REQUESTED,
	COUNTER_PROPOSED,
	WITHDRAWN,
	/** Accepted, then either side called the meeting off. */
	CANCELLED
}
