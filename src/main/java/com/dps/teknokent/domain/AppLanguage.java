package com.dps.teknokent.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/** The language a user reads the app in. In JSON it is the frontend's locale code ("tr", "en"). */
public enum AppLanguage {

	TR,
	EN;

	@JsonValue
	public String code() {
		return name().toLowerCase(Locale.ROOT);
	}

	public Locale locale() {
		return Locale.forLanguageTag(code());
	}

}
