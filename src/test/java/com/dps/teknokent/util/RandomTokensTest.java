package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RandomTokensTest {

	@Test
	void isUrlSafeAndUnpaddedWithTheRequestedEntropy() {
		String token = RandomTokens.urlSafe(32);

		assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");
	}

	@Test
	void neverRepeats() {
		assertThat(RandomTokens.urlSafe(16)).isNotEqualTo(RandomTokens.urlSafe(16));
	}
}
