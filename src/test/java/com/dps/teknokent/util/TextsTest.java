package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.entity.AppUser;

class TextsTest {

	// 23 Sep 2026, 12:00 in Turkey.
	private static final Instant NOON = Instant.parse("2026-09-23T09:00:00Z");

	@Test
	void englishHasEveryTurkishKey() throws IOException {
		assertThat(load(AppLanguage.EN).stringPropertyNames())
				.isEqualTo(load(AppLanguage.TR).stringPropertyNames());
	}

	@Test
	void everyPatternKeepsItsApostrophesAndFillsEveryArgument() throws IOException {
		for (AppLanguage language : AppLanguage.values()) {
			Properties raw = load(language);
			for (String key : raw.stringPropertyNames()) {
				String rendered = Texts.get(language, key, "a0", "a1", "a2", "a3");
				String pattern = raw.getProperty(key);
				assertThat(rendered).as(language + " " + key).doesNotContain("{");
				assertThat(rendered.chars().filter(c -> c == '\'').count()).as(language + " " + key)
						.isEqualTo(pattern.replace("''", "'").chars().filter(c -> c == '\'').count());
			}
		}
	}

	@Test
	void writesInTheReadersLanguage() {
		AppUser reader = new AppUser();

		assertThat(Texts.get(reader, "notification.proposalWaiting.body", "Ayşe ↔ GreenLoop", NOON))
				.isEqualTo("Ayşe ↔ GreenLoop · 23 Eylül Çarşamba · 12:00. Onaylamayı unutma.");

		reader.setPreferredLanguage(AppLanguage.EN);
		assertThat(Texts.get(reader, "notification.proposalWaiting.body", "Ayşe ↔ GreenLoop", NOON))
				.isEqualTo("Ayşe ↔ GreenLoop · Wednesday, 23 September · 12:00. Don't forget to confirm.");
	}

	@Test
	void forRequestFollowsTheCallersLanguageAndIsTurkishOutsideARequest() {
		assertThat(Texts.forRequest("error.meetingInPast")).isEqualTo("Görüşme zamanı gelecekte olmalı.");

		LocaleContextHolder.setLocale(Locale.ENGLISH);
		try {
			assertThat(Texts.forRequest("error.meetingInPast")).isEqualTo("The meeting time must be in the future.");
		} finally {
			LocaleContextHolder.resetLocaleContext();
		}
	}

	@Test
	void inBothPutsTurkishFirst() {
		assertThat(Texts.inBoth(" / ", "mail.teamCode.subject", "Growth Circuit"))
				.isEqualTo("menton: Growth Circuit takım kodunuz / menton: your Growth Circuit team code");
	}

	private static Properties load(AppLanguage language) throws IOException {
		Properties properties = new Properties();
		try (InputStream in = TextsTest.class.getResourceAsStream("/i18n/" + language.code() + "/texts.properties")) {
			properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		return properties;
	}
}
