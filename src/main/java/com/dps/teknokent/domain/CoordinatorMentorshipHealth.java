package com.dps.teknokent.domain;

/** A mentorship as the coordinator sees it: NEW until it becomes active, then graded by meeting cadence. */
public enum CoordinatorMentorshipHealth {
	NEW,
	HEALTHY,
	FOLLOW_UP,
	RISKY
}
