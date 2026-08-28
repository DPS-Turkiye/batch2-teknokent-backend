package com.dps.teknokent.domain;

import java.util.Arrays;

import com.dps.teknokent.exception.InvalidRequestException;

public enum DocumentKind {
	CV("cv", 10 * 1024 * 1024, false),
	CANVAS("canvas", 10 * 1024 * 1024, false),
	PITCH_DECK("pitch-deck", 10 * 1024 * 1024, false),
	MVP("mvp", 10 * 1024 * 1024, false),
	VALIDATION("validation", 10 * 1024 * 1024, false),
	PROFILE_PDF("profile-pdf", 50 * 1024 * 1024, true);

	private final String value;
	private final long maxBytes;
	private final boolean pdfOnly;

	DocumentKind(String value, long maxBytes, boolean pdfOnly) {
		this.value = value;
		this.maxBytes = maxBytes;
		this.pdfOnly = pdfOnly;
	}

	public String value() {
		return value;
	}

	public long maxBytes() {
		return maxBytes;
	}

	public boolean pdfOnly() {
		return pdfOnly;
	}

	public static DocumentKind from(String raw) {
		return Arrays.stream(values())
				.filter(kind -> kind.value.equals(raw))
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException("Unknown document kind."));
	}
}
