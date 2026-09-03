package com.dps.teknokent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TextUtils {

	private TextUtils() {
	}

	// Mutable on purpose: assigned to @ElementCollection fields, and Hibernate's
	// clear() on merge throws on an immutable list.
	public static List<String> normalizedList(List<String> values) {
		return values == null ? new ArrayList<>()
				: values.stream()
						.map(TextUtils::normalizeOptional)
						.filter(Objects::nonNull)
						.distinct()
						.collect(Collectors.toCollection(ArrayList::new));
	}

	public static String trim(String value) {
		return value == null ? null : value.trim();
	}

	public static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	public static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** Up to two uppercase letters for a mentor avatar; mentor_profile.initials is VARCHAR(8). */
	public static String initials(String fullName) {
		if (fullName == null || fullName.isBlank()) {
			return "?";
		}
		StringBuilder builder = new StringBuilder();
		for (String part : fullName.trim().split("\\s+")) {
			if (!part.isBlank() && builder.length() < 2) {
				builder.append(Character.toUpperCase(part.charAt(0)));
			}
		}
		return builder.isEmpty() ? "?" : builder.toString();
	}

	public static String normalizeForComparison(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}
}
