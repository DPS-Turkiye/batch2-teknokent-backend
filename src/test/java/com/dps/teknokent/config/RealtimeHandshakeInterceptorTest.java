package com.dps.teknokent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.service.RealtimeTokenService;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

class RealtimeHandshakeInterceptorTest {

	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final RealtimeTokenService tokens = tokens("test-secret");
	private final RealtimeHandshakeInterceptor interceptor = new RealtimeHandshakeInterceptor(tokens);
	private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();
	private final Map<String, Object> attributes = new HashMap<>();

	@Test
	void aValidTokenOpensTheSocketAndLeavesWhoConnectedAndWhenOnTheSession() {
		Instant signedInAt = Instant.parse("2026-11-08T08:15:30Z");
		Instant before = Instant.now();

		boolean opened = handshake("token=" + tokens.issue(userId, organizationId, signedInAt).token());

		assertThat(opened).isTrue();
		assertThat(servletResponse.getStatus()).isEqualTo(200);
		assertThat(attributes.get(RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE))
				.isEqualTo(new RealtimeIdentity(userId, organizationId, signedInAt));
		assertThat(attributes.get(RealtimeHandshakeInterceptor.CONNECTED_AT_ATTRIBUTE))
				.isInstanceOfSatisfying(Instant.class,
						connectedAt -> assertThat(connectedAt).isBetween(before, Instant.now()));
	}

	@Test
	void aConnectionWithoutATokenIsUnauthorizedAndGetsNoIdentity() {
		assertThat(handshake(null)).isFalse();

		assertThat(servletResponse.getStatus()).isEqualTo(401);
		assertThat(attributes).isEmpty();
	}

	@Test
	void aTokenSignedByAnyoneElseIsUnauthorized() {
		String forged = tokens("another-secret").issue(userId, organizationId, null).token();

		assertThat(handshake("token=" + forged)).isFalse();

		assertThat(servletResponse.getStatus()).isEqualTo(401);
		assertThat(attributes).isEmpty();
	}

	// The identity comes from the signed token alone, never from what the URL claims next to it.
	@Test
	void onlyTheFirstTokenParameterIsReadAndOtherParametersAreIgnored() {
		String token = tokens.issue(userId, organizationId, null).token();

		assertThat(handshake("userId=" + UUID.randomUUID() + "&token=" + token + "&token=forged")).isTrue();

		assertThat(attributes.get(RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE))
				.isEqualTo(new RealtimeIdentity(userId, organizationId, null));
	}

	private boolean handshake(String query) {
		MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/ws");
		servletRequest.setQueryString(query);
		return interceptor.beforeHandshake(new ServletServerHttpRequest(servletRequest),
				new ServletServerHttpResponse(servletResponse), null, attributes);
	}

	private static RealtimeTokenService tokens(String secret) {
		RealtimeTokenService service = new RealtimeTokenService();
		ReflectionTestUtils.setField(service, "tokenSecret", secret);
		ReflectionTestUtils.setField(service, "tokenTtlSeconds", 60L);
		return service;
	}
}
