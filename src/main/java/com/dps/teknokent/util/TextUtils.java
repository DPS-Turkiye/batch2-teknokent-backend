package com.dps.teknokent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TextUtils {

	private TextUtils() {
	}

	/**
	 * Normalizes a list of user-entered chips: blanks dropped, duplicates collapsed,
	 * order preserved. Both {@code app_user_expertise} and
	 * {@code organization_support_area} are {@code @OrderColumn} collections, so the
	 * order the user arranged them in is meaningful and must survive.
	 *
	 * <p>
	 * Returns a <em>mutable</em> list on purpose. These values are assigned to JPA
	 * {@code @ElementCollection} fields, and Hibernate calls {@code clear()} on the
	 * collection when merging a managed entity — handing it {@code Stream.toList()}
	 * or {@code List.of()} throws {@link UnsupportedOperationException} at flush
	 * time, not at assignment, so it surfaces as a 500 rather than a compile error.
	 */
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
