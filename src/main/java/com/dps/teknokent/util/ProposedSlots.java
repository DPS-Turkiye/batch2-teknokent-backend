package com.dps.teknokent.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public final class ProposedSlots {

	public static final String LINE_PREFIX = "proposedSlots:";
	private static final Pattern SLOT = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}$");

	private ProposedSlots() {
	}

	public static List<String> parse(String message) {
		if (message == null || message.isBlank()) {
			return List.of();
		}
		return Arrays.stream(message.split("\\R"))
				.map(String::trim)
				.filter(line -> line.startsWith(LINE_PREFIX))
				.findFirst()
				.map(line -> line.substring(LINE_PREFIX.length()))
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
