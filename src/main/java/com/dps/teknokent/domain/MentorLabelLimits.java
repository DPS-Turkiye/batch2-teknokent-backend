package com.dps.teknokent.domain;

/** The limits on a program's mentor labels; the frontend mirrors them. */
public final class MentorLabelLimits {

	/** The longest label name, in characters. */
	public static final int NAME_MAX_LENGTH = 30;
	/** The most labels one mentor carries in one program. */
	public static final int PER_MENTOR = 5;
	/** The most labels one program keeps, so the picker stays a short list. */
	public static final int PER_PROGRAM = 50;

	private MentorLabelLimits() {
	}
}
