package com.dps.teknokent.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class ProposedSlots {

	public static final String LINE_PREFIX = "proposedSlots:";
	public static final String PREFERRED_LINE_PREFIX = "preferredSlots:";
	private static final Pattern SLOT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$");

	private ProposedSlots() {
	}

	public static List<String> parse(String message) {
		return parse(message, LINE_PREFIX);
	}

	/** The times the mentee picked from the mentor's availability when sending the request. */
	public static List<String> preferred(String requesterMessage) {
		return parse(requesterMessage, PREFERRED_LINE_PREFIX);
	}

	private static List<String> parse(String message, String prefix) {
		if (message == null || message.isBlank()) {
			return List.of();
		}
		return Arrays.stream(message.split("\\R"))
				.map(String::trim)
				.filter(line -> line.startsWith(prefix))
				.findFirst()
				.map(line -> line.substring(prefix.length()))
				.stream()
				.flatMap(value -> Arrays.stream(value.split(",")))
				.map(String::trim)
				.filter(slot -> SLOT.matcher(slot).matches())
				.distinct()
				.toList();
	}

	public static boolean contains(String message, String selectedSlot) {
		String selected = TextUtils.trim(selectedSlot);
		if (selected == null || !SLOT.matcher(selected).matches()) {
			return false;
		}
		return parse(message).contains(selected);
	}
}
