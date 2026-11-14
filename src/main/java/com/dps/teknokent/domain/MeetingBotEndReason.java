package com.dps.teknokent.domain;

import java.util.Locale;

/** Why a recording bot's run ended, from the provider's completion reason. */
public enum MeetingBotEndReason {
	STOPPED,
	LEFT_ALONE,
	NOT_ADMITTED_IN_TIME,
	REJECTED,
	JOIN_FAILED,
	OTHER;

	public static MeetingBotEndReason fromProvider(String reason) {
		if (reason == null || reason.isBlank()) {
			return null;
		}
		return switch (reason.trim().toLowerCase(Locale.ROOT)) {
			case "stopped" -> STOPPED;
			case "left_alone" -> LEFT_ALONE;
			case "awaiting_admission_timeout" -> NOT_ADMITTED_IN_TIME;
			case "awaiting_admission_rejected" -> REJECTED;
			case "join_failure" -> JOIN_FAILED;
			default -> OTHER;
		};
	}
}
