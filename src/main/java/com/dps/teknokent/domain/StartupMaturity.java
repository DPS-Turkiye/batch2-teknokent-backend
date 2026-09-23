package com.dps.teknokent.domain;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.TextUtils;

public enum StartupMaturity {
	IDEA("Fikir"),
	PROTOTYPE("Prototip"),
	MVP("MVP"),
	REVENUE("Gelir"),
	GROWTH("Büyüme");

	private final String value;

	StartupMaturity(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static StartupMaturity fromOrNull(String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) return null;
		for (StartupMaturity maturity : values()) {
			if (maturity.value.equals(normalized)) return maturity;
		}
		return null;
	}

	public static StartupMaturity requireValidOrNull(String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) return null;
		StartupMaturity match = fromOrNull(raw);
		if (match == null) throw new InvalidRequestException("Unknown maturity: " + normalized);
		return match;
	}
}
