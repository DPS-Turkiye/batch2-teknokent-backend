package com.dps.teknokent.domain;

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

	public static StartupFundingStage fromOrNull(String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) return null;
		for (StartupFundingStage stage : values()) {
			if (stage.value.equals(normalized)) return stage;
		}
		return null;
	}

	public static StartupFundingStage requireValidOrNull(String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) return null;
		StartupFundingStage match = fromOrNull(raw);
		if (match == null) throw new InvalidRequestException("Unknown funding stage: " + normalized);
		return match;
	}
}
