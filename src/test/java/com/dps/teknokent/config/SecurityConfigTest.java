package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import com.dps.teknokent.filter.RateLimitingFilter;

class SecurityConfigTest {

	private static final String FRONTEND = "https://menton.com.tr";

	private final SecurityConfig config = new SecurityConfig();

	@BeforeEach
	void trustedFrontend() {
		ReflectionTestUtils.setField(config, "documentsAllowedOrigins", new String[] { FRONTEND });
	}

	@Test
	void devAndLocalProfilesOpenEveryPathToLocalhostOnlyAndWithCredentials() {
		for (String profile : List.of("dev", "local")) {
			CorsConfiguration cors = corsFor(profile).getCorsConfiguration(request("/api/programs"));

			assertThat(cors.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
			assertThat(cors.checkOrigin("http://127.0.0.1:5173")).isEqualTo("http://127.0.0.1:5173");
			assertThat(cors.checkOrigin("https://evil.example")).isNull();
			assertThat(cors.getAllowCredentials()).isTrue();
			assertThat(cors.getAllowedMethods()).contains("PUT", "PATCH", "DELETE");
			assertThat(cors.getExposedHeaders()).containsExactly(RateLimitingFilter.RETRY_AFTER_HEADER);
		}
	}

	@Test
	void outsideDevOnlyTheUploadAndNotificationPathsAnswerABrowserOnTheFrontendsOrigin() {
		CorsConfigurationSource source = corsFor("prod");

		assertThat(source.getCorsConfiguration(request("/api/programs"))).isNull();
		assertThat(source.getCorsConfiguration(request("/api/assistant/chat"))).isNull();

		for (String path : List.of("/api/documents", "/api/profile-analyses", "/api/profile-analyses/preview",
				"/api/profile-analyses/preview/documents/41")) {
			CorsConfiguration uploads = source.getCorsConfiguration(request(path));

			assertThat(uploads.checkOrigin(FRONTEND)).isEqualTo(FRONTEND);
			assertThat(uploads.checkOrigin("http://localhost:3000")).isNull();
			assertThat(uploads.getAllowCredentials()).isFalse();
			assertThat(uploads.getAllowedMethods()).containsExactly("GET", "POST", "OPTIONS");
			assertThat(uploads.getExposedHeaders()).containsExactly(RateLimitingFilter.RETRY_AFTER_HEADER);
		}

		CorsConfiguration notifications = source.getCorsConfiguration(request("/api/notifications/41/read"));
		assertThat(notifications.checkOrigin(FRONTEND)).isEqualTo(FRONTEND);
		assertThat(notifications.checkOrigin("https://evil.example")).isNull();
		assertThat(notifications.getAllowCredentials()).isFalse();
		assertThat(notifications.getAllowedMethods()).contains("PATCH", "DELETE");
	}

	// spring.security.user.password unset must not turn into "any password is fine".
	@Test
	void theServiceCredentialIsComparedAsIsAndNeverMatchesAMissingOne() {
		PasswordEncoder encoder = config.serviceCredentialEncoder();

		assertThat(encoder.encode("s3cret")).isEqualTo("s3cret");
		assertThat(encoder.matches("s3cret", "s3cret")).isTrue();
		assertThat(encoder.matches("s3cret", "S3cret")).isFalse();
		assertThat(encoder.matches("", null)).isFalse();
		assertThat(encoder.matches("s3cret", null)).isFalse();
	}

	// PasswordEncoder's contract allows a null password; a missing one is no match rather than a 500.
	@Test
	void aMissingPasswordNeverMatchesAndEncodesAsNull() {
		PasswordEncoder encoder = config.serviceCredentialEncoder();

		assertThat(encoder.matches(null, "s3cret")).isFalse();
		assertThat(encoder.matches(null, null)).isFalse();
		assertThat(encoder.encode(null)).isNull();
	}

	private CorsConfigurationSource corsFor(String profile) {
		MockEnvironment environment = new MockEnvironment();
		environment.setActiveProfiles(profile);
		return config.corsConfigurationSource(environment);
	}

	private static MockHttpServletRequest request(String path) {
		return new MockHttpServletRequest("GET", path);
	}
}
