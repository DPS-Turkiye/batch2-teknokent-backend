package com.dps.teknokent.domain;

public enum NotificationCategory {
	GENERAL,
	MESSAGE,
	MEETING_REMINDER,
	MEETING_CHANGE,
	TASK_ASSIGNED,
	TASK_DEADLINE,
	STARTUP_ACTIVITY,
	/** A badge awarded or taken back: a decision about the account, so never muted. */
	BADGE
}
