package com.dps.teknokent.domain;

/**
 * What a program entry in a participant's calendar is: one of the program's own events or deadlines, a session of its
 * weekly plan (a lesson, a workshop, a visit) or a special day of the plan (a bootcamp, a demo day).
 */
public enum ProgramCalendarEntryKind {
	EVENT,
	DEADLINE,
	SESSION,
	SPECIAL_DAY;

	public static ProgramCalendarEntryKind of(ProgramEventKind kind) {
		return kind == ProgramEventKind.DEADLINE ? DEADLINE : EVENT;
	}
}
