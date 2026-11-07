package com.dps.teknokent.util;

import java.util.Locale;
import java.util.regex.Pattern;

import com.dps.teknokent.exception.InvalidRequestException;

/** E-mail domain rules shared by sign-up limits and program eligibility. */
public final class EmailDomains {

	private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)+$");
	private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[a-z0-9-]+(\\.[a-z0-9-]+)+$");

	private EmailDomains() {
	}

	/** "@METU.edu.tr " → "metu.edu.tr"; blank → null; anything that is not a domain is refused. */
	public static String normalizeDomain(String raw) {
		String domain = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
		if (domain.startsWith("@")) {
			domain = domain.substring(1);
		}
		if (domain.isEmpty()) {
			return null;
		}
		if (!DOMAIN.matcher(domain).matches()) {
			throw new InvalidRequestException(Texts.forRequest("error.emailDomainInvalid", raw.trim()));
		}
		return domain;
	}

	public static String normalizeEmail(String raw) {
		return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
	}

	public static boolean isEmail(String normalized) {
		return EMAIL.matcher(normalized).matches();
	}

	/** A department subdomain counts as the university's: ceng.metu.edu.tr matches metu.edu.tr. */
	public static boolean matches(String email, String domain) {
		if (domain == null) {
			return true;
		}
		if (email == null) {
			return false;
		}
		int at = email.lastIndexOf('@');
		if (at < 0) {
			return false;
		}
		String own = email.substring(at + 1).trim().toLowerCase(Locale.ROOT);
		return own.equals(domain) || own.endsWith("." + domain);
	}
}
