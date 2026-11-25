package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.dps.teknokent.service.GoogleMeetClient;

import tools.jackson.databind.json.JsonMapper;

class GoogleMeetConfigTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withUserConfiguration(GoogleMeetConfig.class);

	@Test
	void isOffUnlessTheDeploymentTurnsItOn() {
		runner.run(context -> assertThat(context).doesNotHaveBean(GoogleMeetClient.class));
	}

	@Test
	void refusesToStartWithoutTheAccount() {
		assertThatThrownBy(() -> GoogleMeetConfig.credentials("", "meet@menton.com.tr"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> GoogleMeetConfig.credentials("{}", " ")).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void aBrokenKeyIsReportedWithoutItsContents() {
		assertThatThrownBy(
				() -> GoogleMeetConfig.credentials("{\"private_key\":\"do-not-show\"}", "meet@menton.com.tr"))
				.isInstanceOf(IllegalStateException.class)
				.hasNoCause()
				.hasMessageNotContaining("do-not-show");
	}
}
