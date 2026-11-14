package com.dps.teknokent.filter;

import java.io.IOException;
import java.time.Clock;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dps.teknokent.config.RateLimitingConfig.MemoryBuckets;
import com.dps.teknokent.config.RateLimitingConfig.TokenBucket;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The shared Basic credential acts as anyone, so guessing it must be slow: after {@link #MAX_FAILURES} failed Basic
 * sign-ins from one address in a window, that address gets 429 for every Basic attempt, right or wrong, until the
 * window ends. The frontend proxy always sends the right password, so only a guesser ever fails.
 *
 * <p>Counted in this instance's memory, not Redis: every proxied request carries Basic, and a Redis round trip on
 * each one would cost more than the counter is worth. The address is {@link RateLimitingFilter#clientAddress}, which
 * trusts the last X-Forwarded-For entry; that is CloudFront's, unless the port is reachable without it.
 */
public class BasicLoginThrottleFilter extends OncePerRequestFilter {

	static final long MAX_FAILURES = 20;
	static final long WINDOW_MS = 15 * 60_000;
	private static final int MAX_ADDRESSES = 10_000;
	private static final String BASIC_PREFIX = "Basic ";

	private final MemoryBuckets failures;

	public BasicLoginThrottleFilter() {
		this(Clock.systemUTC());
	}

	BasicLoginThrottleFilter(Clock clock) {
		this.failures = new MemoryBuckets(clock, MAX_ADDRESSES);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
			filterChain.doFilter(request, response);
			return;
		}

		TokenBucket allowance = failures.resolve(RateLimitingFilter.clientAddress(request), MAX_FAILURES, WINDOW_MS);
		if (allowance.getRemainingTokens() == 0) {
			long secondsToWait = allowance.getSecondsToWait();
			response.addHeader(RateLimitingFilter.RETRY_AFTER_HEADER, String.valueOf(secondsToWait));
			response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(),
					"Too many failed sign-ins. Please retry after " + secondsToWait + " seconds.");
			return;
		}

		filterChain.doFilter(request, response);

		// A rejected credential stops the chain with no authentication; an accepted one leaves it in the context.
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken
				|| !authentication.isAuthenticated()) {
			allowance.tryConsume(1);
		}
	}
}
