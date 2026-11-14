package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OcrModeTest {

	@Test
	void readsTheLowercaseValuesTheFrontendSends() {
		assertThat(OcrMode.fromWire("off")).contains(OcrMode.OFF);
		assertThat(OcrMode.fromWire("auto")).contains(OcrMode.AUTO);
		assertThat(OcrMode.fromWire("force")).contains(OcrMode.FORCE);
		assertThat(OcrMode.AUTO.wireValue()).isEqualTo("auto");
	}

	@Test
	void anythingElseIsNoMode() {
		assertThat(OcrMode.fromWire("AUTO")).isEmpty();
		assertThat(OcrMode.fromWire(" auto")).isEmpty();
		assertThat(OcrMode.fromWire("")).isEmpty();
		assertThat(OcrMode.fromWire(null)).isEmpty();
	}
}
