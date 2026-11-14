package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.exception.InvalidRequestException;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Whose profile a parsed CV or pitch deck describes. The lowercase value is what the frontend sends and reads, and
 * what {@code profile_analysis.profile_type} stores.
 */
public enum ProfileType {
	/** A startup, described by its founders' pitch deck or company profile. */
	MENTEE("mentee"),
	/** One person looking for a mentor, from their CV. */
	INDIVIDUAL_MENTEE("individual_mentee"),
	/** A mentor's own CV; only ever previewed, never stored. */
	MENTOR("mentor");

	private final String value;

	ProfileType(String value) {
		this.value = value;
	}

	@JsonValue
	public String value() {
		return value;
	}

	/** Strict: an unknown value in a request is the caller's error. */
	@JsonCreator
	public static ProfileType fromValue(@Nullable String raw) {
		ProfileType match = fromValueOrNull(raw);
		if (match == null) {
			throw new InvalidRequestException("Profile type must be mentee, individual_mentee or mentor.");
		}
		return match;
	}

	public static @Nullable ProfileType fromValueOrNull(@Nullable String raw) {
		for (ProfileType type : values()) {
			if (type.value.equals(raw)) {
				return type;
			}
		}
		return null;
	}
}
