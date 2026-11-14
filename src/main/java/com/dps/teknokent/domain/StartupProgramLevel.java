package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.TextUtils;

public enum StartupProgramLevel {
	MVP("MVP"),
	PROTOTYPE("Prototip");

	private final String value;

	StartupProgramLevel(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static @Nullable StartupProgramLevel fromOrNull(@Nullable String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) {
			return null;
		}
		for (StartupProgramLevel level : values()) {
			if (level.value.equals(normalized)) {
				return level;
			}
		}
		return null;
	}

	public static @Nullable StartupProgramLevel requireValidOrNull(@Nullable String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) {
			return null;
		}
		StartupProgramLevel match = fromOrNull(raw);
		if (match == null) {
			throw new InvalidRequestException("Unknown program level: " + normalized);
		}
		return match;
	}
}
