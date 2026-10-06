package com.dps.teknokent.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MeetingSlotTimes {

	public static final ZoneId APP_ZONE = ZoneId.of("Europe/Istanbul");
	/** A meeting can be booked or proposed no sooner than this, so nobody is handed one at short notice. */
	public static final Duration MIN_BOOKING_LEAD = Duration.ofHours(4);
	public static final String TOO_SOON_MESSAGE = "Görüşme saati en erken 4 saat sonrası için seçilebilir.";
	private static final DateTimeFormatter MACHINE =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
	private static final DateTimeFormatter FOR_PEOPLE =
			DateTimeFormatter.ofPattern("d MMMM EEEE · HH:mm", Locale.forLanguageTag("tr-TR"));
	private static final Pattern DECISION_SLOT =
			Pattern.compile("Görüşme saati:\\s*(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2})");

	private MeetingSlotTimes() {
	}

	public static Instant earliestBookable() {
		return Instant.now().plus(MIN_BOOKING_LEAD);
	}

	/** True when every "yyyy-MM-ddTHH:mm" slot is at least {@link #MIN_BOOKING_LEAD} away. */
	public static boolean allBookable(List<String> machineSlots) {
		Instant earliest = earliestBookable();
		return machineSlots.stream()
				.map(MeetingSlotTimes::parseMachineSlot)
				.allMatch(startsAt -> startsAt == null || !startsAt.isBefore(earliest));
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
