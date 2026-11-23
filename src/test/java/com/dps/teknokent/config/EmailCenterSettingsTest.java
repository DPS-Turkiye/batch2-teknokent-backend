package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.domain.EmailBounceSource;

class EmailCenterSettingsTest {

	@Test
	void writesOnlyToListedAddressesAndDomains() {
		EmailCenterSettings settings = settings(" Simulator.amazonses.com, one@menton.test ", "none");

		assertThat(settings.mayWriteTo("bounce@simulator.amazonses.com")).isTrue();
		assertThat(settings.mayWriteTo("one@menton.test")).isTrue();
		assertThat(settings.mayWriteTo("two@menton.test")).isFalse();
		assertThat(settings.mayWriteTo("x@sub.simulator.amazonses.com")).isFalse();
	}

	@Test
	void aBlankListIsNobodyAndAStarIsEverybody() {
		assertThat(settings("", "none").mayWriteTo("a@b.test")).isFalse();
		assertThat(settings("*", "none").mayWriteTo("a@b.test")).isTrue();
	}

	@Test
	void anUnknownBounceSourceStopsTheStart() {
		assertThat(settings("", "ses-events").getBounceSource()).isEqualTo(EmailBounceSource.SES_EVENTS);
		assertThatThrownBy(() -> settings("", "sometimes")).isInstanceOf(IllegalArgumentException.class);
	}

	private static EmailCenterSettings settings(String allowlist, String bounceSource) {
		return new EmailCenterSettings(true, "noreply@menton.test", "info@menton.test", allowlist, 10, 1000, "",
				bounceSource);
	}
}
