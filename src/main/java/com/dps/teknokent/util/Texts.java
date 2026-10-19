package com.dps.teknokent.util;

import java.text.MessageFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.ResourceBundle;

import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.entity.AppUser;

/**
 * Text the backend writes for people (notifications, e-mails, errors), in the reader's language.
 * {@code i18n/texts.properties} is Turkish and also covers any key the English file lacks. Patterns are
 * {@link MessageFormat}, so a literal apostrophe is written {@code ''}. An {@link Instant} argument prints
 * as a Turkey-time date in the reader's language.
 */
public final class Texts {

	public static final String MAIL_LANGUAGE_BREAK = "\n\n----------\n\n";
	private static final String BUNDLE = "i18n/texts";
	// Without this, a missing English key would fall back to the server's own locale before Turkish.
	private static final ResourceBundle.Control NO_SERVER_LOCALE =
			ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

	private Texts() {
	}

	public static String get(AppUser reader, String key, Object... args) {
		return get(reader.getPreferredLanguage(), key, args);
	}

	/** For the answer to the current request (an error, a match reason), in the caller's language. */
	public static String forRequest(String key, Object... args) {
		return get(requestLanguage(), key, args);
	}

	// The frontend's proxy sends Accept-Language; outside a request (schedulers, unit tests) it is Turkish.
	private static AppLanguage requestLanguage() {
		LocaleContext context = LocaleContextHolder.getLocaleContext();
		Locale locale = context == null ? null : context.getLocale();
		return locale != null && AppLanguage.EN.code().equals(locale.getLanguage()) ? AppLanguage.EN : AppLanguage.TR;
	}

	/** For an invitee with no account yet, so no known language: Turkish, then English. */
	public static String inBoth(String separator, String key, Object... args) {
		return get(AppLanguage.TR, key, args) + separator + get(AppLanguage.EN, key, args);
	}

	public static String get(AppLanguage language, String key, Object... args) {
		Object[] shown = Arrays.stream(args)
				.map(arg -> arg instanceof Instant instant ? MeetingSlotTimes.formatForPeople(instant, language) : arg)
				.toArray();
		String pattern = ResourceBundle.getBundle(BUNDLE, language.locale(), NO_SERVER_LOCALE).getString(key);
		return new MessageFormat(pattern, language.locale()).format(shown);
	}
}
