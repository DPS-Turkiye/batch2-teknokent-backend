package com.dps.teknokent.domain;

import java.util.Locale;

/** Where the recording bot of one meeting is, as the meeting provider (Vexa) reports it. */
public enum MeetingBotState {
	NONE,
	REQUESTED,
	JOINING,
	WAITING_FOR_ADMISSION,
	ACTIVE,
	NEEDS_HELP,
	STOPPING,
	COMPLETED,
	FAILED;

	public static MeetingBotState fromProvider(String status) {
		if (status == null || status.isBlank()) {
			return NONE;
		}
		return switch (status.trim().toLowerCase(Locale.ROOT)) {
			case "requested" -> REQUESTED;
			case "joining" -> JOINING;
			case "awaiting_admission" -> WAITING_FOR_ADMISSION;
			case "active" -> ACTIVE;
			case "needs_help" -> NEEDS_HELP;
			case "stopping" -> STOPPING;
			case "completed" -> COMPLETED;
			default -> FAILED;
		};
	}

	/** A bot in these states still holds the meeting: Vexa refuses a second one for the same link. */
	public boolean holdsMeeting() {
		return this == REQUESTED || this == JOINING || this == WAITING_FOR_ADMISSION || this == ACTIVE
				|| this == STOPPING;
	}
}
