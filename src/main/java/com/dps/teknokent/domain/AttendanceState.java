package com.dps.teknokent.domain;

/** Where a participant's program stands for attendance this week. */
public enum AttendanceState {
	/** A program week is running; its sessions are listed with the participant's check-ins. */
	OPEN,
	/** The program's first week has not begun. */
	NOT_STARTED,
	/** The program's last day has passed. */
	ENDED,
	/** The program has no start date or is not running (draft or completed). */
	UNSCHEDULED,
	/** The program's coordinators do not take attendance. */
	DISABLED,
	/** The person is not taking part in any program. */
	NO_PROGRAM
}
