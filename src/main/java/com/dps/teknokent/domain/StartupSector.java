package com.dps.teknokent.domain;

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.TextUtils;

public enum StartupSector {
	SAAS("SaaS"),
	E_COMMERCE("E-ticaret"),
	FINTECH("FinTech"),
	HEALTHTECH("HealthTech"),
	EDTECH("EdTech"),
	GAMING("Oyun"),
	HARDWARE("Donanım"),
	AI("Yapay Zekâ"),
	OTHER("Diğer");

	private final String value;

	StartupSector(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	// Lenient: an unrecognized value reads back as null rather than failing the
	// whole row, so a future option-list change never breaks reading old data.
	public static StartupSector fromOrNull(String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) return null;
		for (StartupSector sector : values()) {
			if (sector.value.equals(normalized)) return sector;
		}
		return null;
	}

	// Strict: for request validation, where an unrecognized value is the client's error.
	public static StartupSector requireValidOrNull(String raw) {
		String normalized = TextUtils.normalizeOptional(raw);
		if (normalized == null) return null;
		StartupSector match = fromOrNull(raw);
		if (match == null) throw new InvalidRequestException("Unknown sector: " + normalized);
		return match;
	}
}
