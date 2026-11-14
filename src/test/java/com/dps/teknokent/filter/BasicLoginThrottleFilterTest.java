package com.dps.teknokent.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;

class BasicLoginThrottleFilterTest {

	private static final String GUESSER = "198.51.100.66";

	private final MutableClock clock = new MutableClock(Instant.parse("2026-11-14T09:00:00Z"));
	private final BasicLoginThrottleFilter filter = new BasicLoginThrottleFilter(clock);
	private final AtomicInteger checked = new AtomicInteger();

	/** Stands in for Spring's Basic check: the right password authenticates, anything else is a 401. */
	private final FilterChain basicCheck = (request, response) -> {
		checked.incrementAndGet();
		SecurityContextHolder.clearContext();
		String header = ((MockHttpServletRequest) request).getHeader("Authorization");
		if (header != null && header.equals(basic("service", "right"))) {
			SecurityContextHolder.getContext().setAuthentication(
					UsernamePasswordAuthenticationToken.authenticated("service", null, List.of()));
		} else if (header != null && header.startsWith("Basic ")) {
			((HttpServletResponse) response).setStatus(401);
		}
	};

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void afterTwentyFailuresAnAddressCannotTryAgainUntilTheWindowEnds() throws Exception {
		for (int i = 0; i < BasicLoginThrottleFilter.MAX_FAILURES; i++) {
			assertThat(call(GUESSER, basic("service", "guess-" + i)).getStatus()).isEqualTo(401);
		}

		MockHttpServletResponse refused = call(GUESSER, basic("service", "guess-20"));

		assertThat(refused.getStatus()).isEqualTo(429);
		assertThat(refused.getHeader(RateLimitingFilter.RETRY_AFTER_HEADER)).isEqualTo("900");
		assertThat(checked).hasValue(20);

		clock.advance(Duration.ofMillis(BasicLoginThrottleFilter.WINDOW_MS));

		assertThat(call(GUESSER, basic("service", "right")).getStatus()).isEqualTo(200);
	}

	// Otherwise a guesser would learn the password from the one attempt that is not refused.
	@Test
	void aBlockedAddressIsRefusedEvenWithTheRightPassword() throws Exception {
		for (int i = 0; i < BasicLoginThrottleFilter.MAX_FAILURES; i++) {
			call(GUESSER, basic("service", "guess-" + i));
		}

		assertThat(call(GUESSER, basic("service", "right")).getStatus()).isEqualTo(429);
		assertThat(call("198.51.100.67", basic("service", "right")).getStatus()).isEqualTo(200);
	}

	@Test
	void theRightPasswordIsNeverCountedHoweverOftenItIsSent() throws Exception {
		for (int i = 0; i < 100; i++) {
			assertThat(call(GUESSER, basic("service", "right")).getStatus()).isEqualTo(200);
		}
	}

	@Test
	void requestsWithoutBasicAreLeftAlone() throws Exception {
		for (int i = 0; i < 30; i++) {
			assertThat(call(GUESSER, null).getStatus()).isEqualTo(200);
			assertThat(call(GUESSER, "Bearer not-a-firebase-token").getStatus()).isEqualTo(200);
		}

		assertThat(call(GUESSER, basic("service", "right")).getStatus()).isEqualTo(200);
	}

	private MockHttpServletResponse call(String address, String authorization) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/programs");
		request.addHeader("X-Forwarded-For", address);
		if (authorization != null) {
			request.addHeader("Authorization", authorization);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, basicCheck);
		return response;
	}

	private static String basic(String user, String password) {
		return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}
}
