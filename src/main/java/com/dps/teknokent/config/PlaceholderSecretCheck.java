package com.dps.teknokent.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a released build while a secret still holds the {@code change-me} placeholder that
 * {@code .env.example} and {@code application.yml} ship with: on the shared Basic credential that would let anyone
 * act as the frontend, and on the realtime secret anyone could mint room tokens.
 *
 * <p>A released build is told apart by {@code APP_VERSION}: only the deploy workflow builds the image with it set (to
 * the release tag); the Dockerfile's default is {@code local}, and {@code docker compose up}, {@code dev/}, the tests
 * and CI leave it at that or unset. Keying on the Spring profile would be weaker, since {@code .env.example} sets
 * {@code dev} and a copy of it is the likeliest way a placeholder reaches a server at all.
 */
@Component
public class PlaceholderSecretCheck {

	/** Property, and the environment variable that sets it, for the message. */
	static final Map<String, String> SECRETS = Map.of(
			"spring.security.user.password", "SECURITY_PASSWORD",
			"spring.datasource.password", "DATABASE_PASSWORD",
			"app.realtime.token-secret", "REALTIME_TOKEN_SECRET");

	private static final Set<String> PLACEHOLDERS = Set.of("change-me", "changeme", "change_me");
	private static final String UNRELEASED_VERSION = "local";

	public PlaceholderSecretCheck(Environment environment) {
		String version = environment.getProperty("APP_VERSION", UNRELEASED_VERSION).trim();
		if (version.isEmpty() || UNRELEASED_VERSION.equals(version)) {
			return;
		}
		List<String> placeholders = placeholderSecrets(environment);
		if (!placeholders.isEmpty()) {
			throw new IllegalStateException("Release " + version + " refuses to start with a placeholder secret in "
					+ String.join(", ", placeholders) + "; set a real value in the server's environment.");
		}
	}

	static List<String> placeholderSecrets(Environment environment) {
		List<String> found = new ArrayList<>();
		SECRETS.forEach((property, variable) -> {
			String value = environment.getProperty(property);
			if (value != null && PLACEHOLDERS.contains(value.trim().toLowerCase(Locale.ROOT))) {
				found.add(variable);
			}
		});
		found.sort(Comparator.naturalOrder());
		return found;
	}
}
