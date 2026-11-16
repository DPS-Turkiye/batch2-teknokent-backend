package com.dps.teknokent.domain;

/** Where one team stands with a program Ödev, as the coordinator's list shows it. */
public enum ProgramAssignmentSubmissionState {
	/** Handed in on or before the due date, or with no due date. */
	SUBMITTED,
	/** Handed in after the due date. */
	LATE,
	/** Not handed in and the due date has passed. */
	OVERDUE,
	/** Not handed in yet; the due date is still ahead or there is none. */
	OPEN
}
