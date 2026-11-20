package com.dps.teknokent.domain;

/**
 * How a membership ended; a leader leaving with a successor is LEFT too, and MOVED is an admin's or coordinator's move.
 */
public enum TeamExitReason {
	LEFT,
	REMOVED,
	MOVED
}
