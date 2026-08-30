package com.dps.teknokent.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

	@Test
	void nextCode_isEightCharactersFromSafeAlphabet() {
		InviteCodeGenerator generator = new InviteCodeGenerator();

		String code = generator.nextCode();

		assertThat(code).hasSize(8);
		assertThat(code).matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}");
	}

	@Test
	void nextUnique_skipsTakenCodes() {
		InviteCodeGenerator generator = new InviteCodeGenerator();

		String code = generator.nextUnique("AAAAAAAA"::equals);

		assertThat(code).isNotEqualTo("AAAAAAAA");
		assertThat(code).hasSize(8);
	}
}
