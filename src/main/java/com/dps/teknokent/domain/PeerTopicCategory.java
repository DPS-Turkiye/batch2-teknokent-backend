package com.dps.teknokent.domain;

import java.util.Arrays;
import java.util.Locale;

/** The board's eight peer-mentoring categories (frontend PeerTopicCategory mirrors them). */
public enum PeerTopicCategory {
	ACADEMIC_DEVELOPMENT("Akademik Gelişim"),
	CAREER_INTERNSHIP("Kariyer ve Staj"),
	TECHNICAL_DIGITAL("Teknik ve Dijital Beceriler"),
	RESEARCH_ACADEMIC_CAREER("Araştırma ve Akademik Kariyer"),
	ENTREPRENEURSHIP("Girişimcilik ve Proje Geliştirme"),
	CAMPUS_LIFE("Üniversite ve Kampüs Yaşamı"),
	PERSONAL_PROFESSIONAL("Kişisel ve Profesyonel Beceriler"),
	INTERNATIONAL("Uluslararası Fırsatlar");

	private final String turkishLabel;

	PeerTopicCategory(String turkishLabel) {
		this.turkishLabel = turkishLabel;
	}

	/**
	 * The category a stored text names, by code or by the board's Turkish label (a mentor profile's topics are free
	 * text until the peer-mentor wizard stores codes); null for anything else.
	 */
	public static PeerTopicCategory fromText(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String value = text.trim();
		Locale tr = Locale.forLanguageTag("tr");
		return Arrays.stream(values())
				.filter(category -> category.name().equals(value)
						|| category.turkishLabel.toLowerCase(tr).equals(value.toLowerCase(tr)))
				.findFirst()
				.orElse(null);
	}
}
