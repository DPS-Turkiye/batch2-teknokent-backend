package com.dps.teknokent.domain;

/** Whether a participant can check in this week, and if not, why. */
public enum AttendanceState {
	/** The running week is open and the participant has not checked in yet. */
	OPEN,
	CHECKED_IN,
	/** The program's first week has not begun. */
	NOT_STARTED,
	/** The program's last day has passed. */
	ENDED,
	/** The program has no start date or is not running (draft or completed). */
	UNSCHEDULED,
	/** The person is not taking part in any program. */
	NO_PROGRAM
}
