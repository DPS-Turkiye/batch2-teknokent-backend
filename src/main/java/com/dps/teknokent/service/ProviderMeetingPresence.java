package com.dps.teknokent.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dps.teknokent.dto.provider.ProviderBotDetails;

public final class ProviderMeetingPresence {

	static final String BOT_NAME = "Mentor Bot";
	static final int UNKNOWN = -1;

	private ProviderMeetingPresence() {}

	public static boolean looksFinished(String status) {
		if (status == null || status.isBlank()) {
			return false;
		}
		String normalized = status.trim().toLowerCase(Locale.ROOT);
		return normalized.equals("completed")
				|| normalized.equals("complete")
				|| normalized.equals("stopped")
				|| normalized.equals("finished")
				|| normalized.equals("failed")
				|| normalized.equals("ended")
				|| normalized.equals("destroyed");
	}

	public static int humanCount(ProviderBotDetails details) {
		if (details == null) {
			return UNKNOWN;
		}
		List<Object> raw = details.participants() != null ? details.participants() : details.attendees();
		if (raw == null) {
			return UNKNOWN;
		}
		int count = 0;
		for (Object item : raw) {
			String name = nameOf(item);
			if (name.isBlank() || isBot(name)) {
				continue;
			}
			count++;
		}
		return count;
	}

	static String nameOf(Object item) {
		if (item == null) {
			return "";
		}
		if (item instanceof String text) {
			return text.trim();
		}
		if (item instanceof Map<?, ?> map) {
			Object named = first(map, "name", "display_name", "displayName", "full_name", "fullName", "speaker");
			return named == null ? "" : String.valueOf(named).trim();
		}
		return String.valueOf(item).trim();
	}

	private static Object first(Map<?, ?> map, String... keys) {
		for (String key : keys) {
			if (map.containsKey(key) && map.get(key) != null) {
				return map.get(key);
			}
		}
		return null;
	}

	static boolean isBot(String name) {
		String normalized = name.toLowerCase(Locale.ROOT);
		return normalized.contains(BOT_NAME.toLowerCase(Locale.ROOT))
				|| normalized.contains("vexa");
	}
}
