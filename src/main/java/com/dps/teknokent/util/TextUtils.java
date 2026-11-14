package com.dps.teknokent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;

public final class TextUtils {

	private TextUtils() {
	}

	// Mutable on purpose: assigned to @ElementCollection fields, and Hibernate's
	// clear() on merge throws on an immutable list.
	public static List<String> normalizedList(@Nullable List<String> values) {
		return values == null ? new ArrayList<>()
				: values.stream()
						.map(TextUtils::normalizeOptional)
						.filter(Objects::nonNull)
						.distinct()
						.collect(Collectors.toCollection(ArrayList::new));
	}

	@Contract("null -> null; !null -> !null")
	public static @Nullable String trim(@Nullable String value) {
		return value == null ? null : value.trim();
	}

	public static String nullToEmpty(@Nullable String value) {
		return value == null ? "" : value;
	}

	public static @Nullable String normalizeOptional(@Nullable String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** Up to two uppercase letters for a mentor avatar; mentor_profile.initials is VARCHAR(8). */
	public static String initials(@Nullable String fullName) {
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

	/**
	 * Invite codes are generated from an uppercase alphabet and looked up with an
	 * exact match, so a code typed in lowercase — or mangled by a phone keyboard's
	 * auto-capitalisation — has to be folded first. Locale.ROOT because Turkish
	 * would turn "i" into "İ".
	 */
	public static String normalizeInviteCode(@Nullable String value) {
		return nullToEmpty(trim(value)).toUpperCase(Locale.ROOT);
	}

	public static String normalizeForComparison(@Nullable String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}
}
