package com.dps.teknokent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPiiRedactionServiceTest {
	private final LocalPiiRedactionService service = new LocalPiiRedactionService();

	@Test
	void masksContactDetailsBeforeTheAiCall() {
		var result = service.redact("Ada Lovelace ada@example.com +90 532 111 22 33");

		assertThat(result.text()).contains("[REDACTED_EMAIL]", "[REDACTED_PHONE]");
		assertThat(result.text()).doesNotContain("ada@example.com", "532 111 22 33");
		assertThat(result.totalRedacted()).isEqualTo(2);
	}
}
