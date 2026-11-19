package com.dps.teknokent.domain;

/** Whether a program's mentees and accepted mentors see one week of its plan; the coordinator always does. */
public enum WeekVisibility {
	/** Seen once the week has begun, as before this setting existed. */
	AUTO,
	/** Seen now, before the week begins. */
	SHOWN,
	/** Not seen at all, even once the week has begun or ended. */
	HIDDEN
}
