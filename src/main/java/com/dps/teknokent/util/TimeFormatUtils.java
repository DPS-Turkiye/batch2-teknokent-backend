package com.dps.teknokent.util;

import org.jspecify.annotations.Nullable;

public final class TimeFormatUtils {

	private TimeFormatUtils() {
	}

	public static String formatTime(@Nullable Double start) {
		if (start == null) {
			return "";
		}
		int m = (int) (start / 60);
		int s = (int) (start % 60);
		return String.format("%02d:%02d", m, s);
	}
}