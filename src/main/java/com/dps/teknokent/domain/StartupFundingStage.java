package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.TextUtils;

public enum StartupFundingStage {
	BOOTSTRAPPED("Kendi kaynaklarıyla"),
	PRE_SEED("Pre-seed"),
	SEED("Seed"),
	SERIES_A("Seri A"),
	SERIES_B_PLUS("Seri B ve sonrası");

	private final String value;

	StartupFundingStage(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static @Nullable StartupFundingStage fromOrNull(@Nullable String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) {
			return null;
		}
		for (StartupFundingStage stage : values()) {
			if (stage.value.equals(normalized)) {
				return stage;
			}
		}
		return null;
	}

	public static @Nullable StartupFundingStage requireValidOrNull(@Nullable String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) {
			return null;
		}
		StartupFundingStage match = fromOrNull(raw);
		if (match == null) {
			throw new InvalidRequestException("Unknown funding stage: " + normalized);
		}
		return match;
	}
}
