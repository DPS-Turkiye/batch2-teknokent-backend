package com.dps.teknokent.service;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.dto.responses.RealtimeTokenResponse;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

import static org.assertj.core.api.Assertions.assertThat;

class RealtimeTokenServiceTest {

	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");

	private RealtimeTokenService service(long ttlSeconds) {
		RealtimeTokenService service = new RealtimeTokenService();
		ReflectionTestUtils.setField(service, "tokenSecret", "test-secret");
		ReflectionTestUtils.setField(service, "tokenTtlSeconds", ttlSeconds);
		return service;
	}

	@Test
	void acceptsATokenItIssuedItself() {
		RealtimeTokenService service = service(60);

		RealtimeTokenResponse issued = service.issue(userId, organizationId);
		RealtimeIdentity identity = service.verify(issued.token());

		assertThat(identity).isNotNull();
		assertThat(identity.userId()).isEqualTo(userId);
		assertThat(identity.organizationId()).isEqualTo(organizationId);
	}

	@Test
	void rejectsTokensWithATamperedPayloadOrSignature() {
		RealtimeTokenService service = service(60);
		String token = service.issue(userId, organizationId).token();

		assertThat(service.verify(token.substring(0, token.lastIndexOf('.')) + ".forged")).isNull();
		assertThat(service.verify("not-a-token")).isNull();
		assertThat(service.verify(null)).isNull();
	}

	@Test
	void rejectsTokensSignedWithAnotherSecret() {
		RealtimeTokenService other = service(60);
		ReflectionTestUtils.setField(other, "tokenSecret", "another-secret");

		assertThat(service(60).verify(other.issue(userId, organizationId).token())).isNull();
	}

	@Test
	void rejectsExpiredTokens() {
		RealtimeTokenService service = service(-1);

		assertThat(service.verify(service.issue(userId, organizationId).token())).isNull();
	}
}
