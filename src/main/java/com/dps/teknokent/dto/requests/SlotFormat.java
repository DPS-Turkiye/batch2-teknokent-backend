package com.dps.teknokent.dto.requests;

/** A meeting time as requests carry it, the same format mentorship requests use. */
public final class SlotFormat {

	public static final String PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}";

	private SlotFormat() {
	}
}
