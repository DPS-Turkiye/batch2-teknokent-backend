package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PlaceholderSecretCheckTest {

	@Test
	void aReleaseWithThePlaceholderBasicPasswordRefusesToStart() {
		MockEnvironment environment = realSecrets()
				.withProperty("APP_VERSION", "v1.42.0")
				.withProperty("spring.security.user.password", "change-me");

		assertThatThrownBy(() -> new PlaceholderSecretCheck(environment))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("v1.42.0")
				.hasMessageContaining("SECURITY_PASSWORD")
				.hasMessageNotContaining("change-me");
	}

	@Test
	void everyPlaceholderSecretIsNamedWhateverItsCaseOrSpacing() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty("APP_VERSION", "v1.42.0")
				.withProperty("spring.security.user.password", " Change-Me ")
				.withProperty("spring.datasource.password", "CHANGEME")
				.withProperty("app.realtime.token-secret", "change_me");

		assertThat(PlaceholderSecretCheck.placeholderSecrets(environment))
				.containsExactly("DATABASE_PASSWORD", "REALTIME_TOKEN_SECRET", "SECURITY_PASSWORD");
		assertThatThrownBy(() -> new PlaceholderSecretCheck(environment))
				.hasMessageContaining("DATABASE_PASSWORD, REALTIME_TOKEN_SECRET, SECURITY_PASSWORD");
	}

	@Test
	void aReleaseWithRealSecretsStarts() {
		MockEnvironment environment = realSecrets().withProperty("APP_VERSION", "v1.42.0");

		assertThatCode(() -> new PlaceholderSecretCheck(environment)).doesNotThrowAnyException();
	}

	// docker compose, dev/editions, the tests and CI all run with the template's values.
	@Test
	void aLocalBuildStartsWithThePlaceholdersFromTheTemplate() {
		MockEnvironment placeholders = new MockEnvironment()
				.withProperty("spring.security.user.password", "change-me")
				.withProperty("spring.datasource.password", "change-me")
				.withProperty("app.realtime.token-secret", "change-me");

		assertThatCode(() -> new PlaceholderSecretCheck(placeholders)).doesNotThrowAnyException();
		assertThatCode(() -> new PlaceholderSecretCheck(placeholders.withProperty("APP_VERSION", "local")))
				.doesNotThrowAnyException();
		assertThatCode(() -> new PlaceholderSecretCheck(placeholders.withProperty("APP_VERSION", " ")))
				.doesNotThrowAnyException();
	}

	private static MockEnvironment realSecrets() {
		return new MockEnvironment()
				.withProperty("spring.security.user.password", "q8V2-long-random-value")
				.withProperty("spring.datasource.password", "another-real-one")
				.withProperty("app.realtime.token-secret", "and-a-third");
	}
}
