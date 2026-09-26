package com.dps.teknokent.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MeetingSlotTimes {

	public static final ZoneId APP_ZONE = ZoneId.of("Europe/Istanbul");
	private static final DateTimeFormatter MACHINE =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
	private static final DateTimeFormatter FOR_PEOPLE =
			DateTimeFormatter.ofPattern("d MMMM EEEE · HH:mm", Locale.forLanguageTag("tr-TR"));
	private static final Pattern DECISION_SLOT =
			Pattern.compile("Görüşme saati:\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2})");

	private MeetingSlotTimes() {
	}

	/** "23 Eylül Çarşamba · 12:00" in Turkey time, for text people read. */
	public static String formatForPeople(Instant instant) {
		return FOR_PEOPLE.format(instant.atZone(APP_ZONE));
	}

	public static Instant parseMachineSlot(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			LocalDateTime local = LocalDateTime.parse(value.trim(), MACHINE);
			return local.atZone(APP_ZONE).toInstant();
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	public static Instant parseFromDecisionMessage(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		Matcher matcher = DECISION_SLOT.matcher(message);
		if (!matcher.find()) {
			return null;
		}
		return parseMachineSlot(matcher.group(1));
	}
}
