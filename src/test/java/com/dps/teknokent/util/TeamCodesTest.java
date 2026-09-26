package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TeamCodesTest {

	@Test
	void thePrefixIsTheProgramsLastWordAndItsYear() {
		assertThat(TeamCodes.prefix("METU BİGG 2026")).isEqualTo("BIGG26");
		assertThat(TeamCodes.prefix("METU Stars 2026")).isEqualTo("STARS26");
		assertThat(TeamCodes.prefix("Hızlandırma Programı")).isEqualTo("PROGRA");
		assertThat(TeamCodes.prefix("2026")).isEqualTo("TAKIM26");
	}

	@Test
	void codesUseOnlyUnambiguousCharacters() {
		for (int i = 0; i < 200; i++) {
			assertThat(TeamCodes.next("BIGG26")).matches("BIGG26-[A-HJKMNP-Z2-9]{4}");
		}
	}
}
