package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LtiPropertiesTest {

	private static final String MOODLE = "https://moodle.example";

	private static LtiProperties with(String issuer, String clientId, String deploymentIds, String authUrl,
			String jwksUrl, String launchUrl) {
		return new LtiProperties(issuer, clientId, deploymentIds, authUrl, jwksUrl, launchUrl, null);
	}

	// The private key is optional (a throwaway one is made), everything Moodle's registration gives is not.
	@Test
	void isConfiguredOnceEveryValueFromTheMoodleRegistrationIsSet() {
		assertThat(with(MOODLE, "client-1", "1", MOODLE + "/auth", MOODLE + "/certs", "https://menton.example/lti")
				.configured()).isTrue();
	}

	@Test
	void isNotConfiguredWhileAnyOneValueIsMissingOrBlank() {
		assertThat(with(" ", "client-1", "1", MOODLE + "/auth", MOODLE + "/certs", "https://menton.example/lti")
				.configured()).isFalse();
		assertThat(with(MOODLE, null, "1", MOODLE + "/auth", MOODLE + "/certs", "https://menton.example/lti")
				.configured()).isFalse();
		assertThat(with(MOODLE, "client-1", "", MOODLE + "/auth", MOODLE + "/certs", "https://menton.example/lti")
				.configured()).isFalse();
		assertThat(with(MOODLE, "client-1", "1", null, MOODLE + "/certs", "https://menton.example/lti")
				.configured()).isFalse();
		assertThat(with(MOODLE, "client-1", "1", MOODLE + "/auth", " ", "https://menton.example/lti")
				.configured()).isFalse();
		assertThat(with(MOODLE, "client-1", "1", MOODLE + "/auth", MOODLE + "/certs", null)
				.configured()).isFalse();
	}

	@Test
	void readsTheDeploymentIdsAsACleanList() {
		assertThat(with(MOODLE, "client-1", " 1, ,2 ,", null, null, null).deploymentIdList()).containsExactly("1", "2");
		assertThat(with(MOODLE, "client-1", null, null, null, null).deploymentIdList()).isEmpty();
		assertThat(with(MOODLE, "client-1", "   ", null, null, null).deploymentIdList()).isEmpty();
	}
}
