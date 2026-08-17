package com.dps.teknokent.util;

import java.util.Locale;

public final class TextUtils {

	private TextUtils() {
	}

	public static String trim(String value) {
		return value == null ? null : value.trim();
	}

	public static String normalizeOptional(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	public static String normalizeForComparison(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
	}
}
