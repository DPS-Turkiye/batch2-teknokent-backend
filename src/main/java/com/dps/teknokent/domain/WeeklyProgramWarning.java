package com.dps.teknokent.domain;

/** What the review of a weekly program file should point out; the screen words each one. */
public enum WeeklyProgramWarning {
	/** The file was longer than one read takes; its end was left out. */
	TEXT_CUT,
	/** The file prints a date for these weeks that falls outside that week on the program calendar. */
	DATES_DIFFER,
	/** These weeks were left out: past the program's last week, repeated, or without a title. */
	WEEKS_DROPPED,
	/** The AI's phase ranges did not fit the weeks, so the phases were rebuilt from each week's phase. */
	PHASES_REBUILT,
	/** More phases than a program keeps; the last ones were merged. */
	PHASES_MERGED,
	/** No phases could be read; the coordinator adds them by hand. */
	NO_PHASES
}
