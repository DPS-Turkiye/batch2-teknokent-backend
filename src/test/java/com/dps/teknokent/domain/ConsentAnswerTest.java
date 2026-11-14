package com.dps.teknokent.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.exception.InvalidRequestException;

class ConsentAnswerTest {

	@Test
	void anAcceptedConsentNamingItsTextVersionPasses() {
		assertThatCode(() -> new ConsentAnswer(true, "cv-parsing-2026-11-13").requireAccepted())
				.doesNotThrowAnyException();
	}

	@Test
	void aRefusedConsentIsRefused() {
		assertThatThrownBy(() -> new ConsentAnswer(false, "cv-parsing-2026-11-13").requireAccepted())
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consent is required");
	}

	@Test
	void aConsentWithoutTheTextVersionIsRefused() {
		assertThatThrownBy(() -> new ConsentAnswer(true, null).requireAccepted())
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consentTextVersion");
		assertThatThrownBy(() -> new ConsentAnswer(true, "  ").requireAccepted())
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consentTextVersion");
	}

	@Test
	void aTextVersionThatIsNotAShortTokenIsRefused() {
		assertThatThrownBy(() -> new ConsentAnswer(true, "v1; drop table").requireAccepted())
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consentTextVersion");
		assertThatThrownBy(() -> new ConsentAnswer(true, "v".repeat(65)).requireAccepted())
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("consentTextVersion");
	}
}
