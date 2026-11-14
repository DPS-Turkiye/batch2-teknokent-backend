package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.dto.responses.RealtimeTokenResponse;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

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

		RealtimeTokenResponse issued = service.issue(userId, organizationId, null);
		RealtimeIdentity identity = service.verify(issued.token());

		assertThat(identity).isNotNull();
		assertThat(identity.userId()).isEqualTo(userId);
		assertThat(identity.organizationId()).isEqualTo(organizationId);
	}

	@Test
	void carriesTheSignInTheTokenWasIssuedFor() {
		RealtimeTokenService service = service(60);
		Instant signedInAt = Instant.parse("2026-11-08T08:15:30.123Z");

		RealtimeIdentity identity = service.verify(service.issue(userId, organizationId, signedInAt).token());

		assertThat(identity.signedInAt()).isEqualTo(signedInAt);
		assertThat(service.verify(service.issue(userId, organizationId, null).token()).signedInAt()).isNull();
	}

	@Test
	void rejectsTokensWithATamperedPayloadOrSignature() {
		RealtimeTokenService service = service(60);
		String token = service.issue(userId, organizationId, null).token();

		assertThat(service.verify(token.substring(0, token.lastIndexOf('.')) + ".forged")).isNull();
		assertThat(service.verify("not-a-token")).isNull();
		assertThat(service.verify(null)).isNull();
	}

	@Test
	void rejectsTokensSignedWithAnotherSecret() {
		RealtimeTokenService other = service(60);
		ReflectionTestUtils.setField(other, "tokenSecret", "another-secret");

		assertThat(service(60).verify(other.issue(userId, organizationId, null).token())).isNull();
	}

	@Test
	void rejectsExpiredTokens() {
		RealtimeTokenService service = service(-1);

		assertThat(service.verify(service.issue(userId, organizationId, null).token())).isNull();
	}

	@Test
	void theTokenSaysWhenItExpiresAndThatIsItsConfiguredLifetimeFromNow() {
		Instant before = Instant.now();

		RealtimeTokenResponse issued = service(60).issue(userId, organizationId, null);

		assertThat(issued.expiresAt()).isBetween(before.plusSeconds(60), Instant.now().plusSeconds(60));
	}

	@Test
	void rejectsABlankTokenOneWithoutAPayloadAndOneThatIsNotBase64() {
		RealtimeTokenService service = service(60);

		assertThat(service.verify("  ")).isNull();
		assertThat(service.verify(".signature")).isNull();
		assertThat(service.verify("%%%.signature")).isNull();
	}

	// Only this backend can sign, but a signed payload it would never write must still not become an identity.
	@Test
	void rejectsACorrectlySignedPayloadThatIsNotAnIdentity() throws Exception {
		RealtimeTokenService service = service(60);
		long inAMinute = Instant.now().plusSeconds(60).getEpochSecond();

		assertThat(service.verify(signed(userId + ":" + organizationId + ":" + inAMinute)))
				.isEqualTo(new RealtimeIdentity(userId, organizationId, null));
		assertThat(service.verify(signed(userId + ":" + organizationId))).isNull();
		assertThat(service.verify(signed(userId + ":" + organizationId + ":" + inAMinute + ":1:extra"))).isNull();
		assertThat(service.verify(signed("not-a-uuid:" + organizationId + ":" + inAMinute))).isNull();
		assertThat(service.verify(signed(userId + ":" + organizationId + ":soon"))).isNull();
		assertThat(service.verify(signed(userId + ":" + organizationId + ":" + inAMinute + ":yesterday"))).isNull();
	}

	// An empty secret would make every token forgeable, so nothing is issued or accepted without one.
	@Test
	void neitherIssuesNorAcceptsATokenWithoutASecret() {
		String token = service(60).issue(userId, organizationId, null).token();
		RealtimeTokenService unconfigured = service(60);
		ReflectionTestUtils.setField(unconfigured, "tokenSecret", " ");

		assertThatThrownBy(() -> unconfigured.issue(userId, organizationId, null))
				.isInstanceOf(InvalidRequestException.class);
		assertThatThrownBy(() -> unconfigured.verify(token)).isInstanceOf(InvalidRequestException.class);
		ReflectionTestUtils.setField(unconfigured, "tokenSecret", null);
		assertThatThrownBy(() -> unconfigured.verify(token)).isInstanceOf(InvalidRequestException.class);
	}

	private static String signed(String payload) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
		byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
		return encoder.encodeToString(bytes) + "." + encoder.encodeToString(mac.doFinal(bytes));
	}
}
