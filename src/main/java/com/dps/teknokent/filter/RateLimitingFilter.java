package com.dps.teknokent.filter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.config.RateLimitingConfig.TokenBucket;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@ConditionalOnProperty(name = "rate-limiting.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

	private final RateLimitingConfig rateLimitingConfig;

	private static final String[] RATE_LIMITED_PATHS = {
			"/api/assistant/",
	};

	/** Exposed to browsers in SecurityConfig, so the CV upload can say how long to wait. */
	public static final String RETRY_AFTER_HEADER = "X-Rate-Limit-Retry-After-Seconds";

	private static final String CV_PARSE_PATH = "/api/profile-analyses";
	private static final String CV_PREVIEW_PATH = CV_PARSE_PATH + "/preview";
	private static final String SYLLABUS_PARSE_PATH = "/api/coordinator/programs/syllabus/phases";
	private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();
	private static final Pattern WEEKLY_PROGRAM_READ_PATH = Pattern
			.compile("/api/coordinator/programs/[^/]+/weekly-program/read");

	/**
	 * Each CV, syllabus or weekly program parse runs OCR and a paid LLM call, so it gets a far tighter budget than the
	 * general limit.
	 */
	private static final long CV_PARSE_CAPACITY = 10;
	private static final long CV_PARSE_WINDOW_MS = 10 * 60_000;

	/**
	 * The preview opens to any verified Firebase login, account or not, and sign-up is open, so the per-account
	 * budget alone would let a script that makes logins parse without end. Generous enough for a room of people
	 * signing up behind one office or campus address.
	 */
	private static final long CV_PREVIEW_PER_ADDRESS_CAPACITY = 30;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		List<TokenBucket> buckets = bucketsFor(request);
		if (buckets.isEmpty()) {
			filterChain.doFilter(request, response);
			return;
		}

		long remaining = Long.MAX_VALUE;
		// In order, stopping at the first refusal: a caller over its own budget spends nothing from the address's.
		for (TokenBucket bucket : buckets) {
			if (!bucket.tryConsume(1)) {
				long secondsToWait = bucket.getSecondsToWait();
				response.addHeader(RETRY_AFTER_HEADER, String.valueOf(secondsToWait));
				response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(),
						"Too many requests. Please retry after " + secondsToWait + " seconds.");
				return;
			}
			remaining = Math.min(remaining, bucket.getRemainingTokens());
		}
		response.addHeader("X-Rate-Limit-Remaining", String.valueOf(remaining));
		filterChain.doFilter(request, response);
	}

	private List<TokenBucket> bucketsFor(HttpServletRequest request) {
		// Decoded, as the router sees it: "/weekly-program/%72ead" must not slip past the tight budget.
		String path = URL_PATH_HELPER.getPathWithinApplication(request);
		boolean post = "POST".equalsIgnoreCase(request.getMethod());
		if (post && isCvPreview(path)) {
			return List.of(
					rateLimitingConfig.resolveBucket(
							"cv_parse:" + getCallerIdentifier(request), CV_PARSE_CAPACITY, CV_PARSE_WINDOW_MS),
					rateLimitingConfig.resolveBucket(
							"cv_preview_ip:" + clientAddress(request), CV_PREVIEW_PER_ADDRESS_CAPACITY,
							CV_PARSE_WINDOW_MS));
		}
		if (post && isCvParse(path)) {
			return List.of(rateLimitingConfig.resolveBucket(
					"cv_parse:" + getCallerIdentifier(request), CV_PARSE_CAPACITY, CV_PARSE_WINDOW_MS));
		}
		if (shouldApplyRateLimit(request.getRequestURI())) {
			return List.of(rateLimitingConfig.resolveBucket(clientAddress(request)));
		}
		return List.of();
	}

	private static boolean isCvPreview(String path) {
		return CV_PREVIEW_PATH.equals(path) || path.startsWith(CV_PREVIEW_PATH + "/documents/");
	}

	private static boolean isCvParse(String path) {
		return CV_PARSE_PATH.equals(path)
				|| SYLLABUS_PARSE_PATH.equals(path)
				|| WEEKLY_PROGRAM_READ_PATH.matcher(path).matches();
	}

	/**
	 * Keyed per account rather than per address, so founders sharing one office network do not use up each
	 * other's budget. This filter runs after Spring Security, so the caller is already resolved; the service
	 * Basic login is one shared identity and falls back to the address.
	 */
	private String getCallerIdentifier(HttpServletRequest request) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Object principal = authentication == null ? null : authentication.getPrincipal();
		if (principal instanceof AppUserPrincipal user) {
			return "user:" + user.id();
		}
		if (principal instanceof FirebaseIdentity identity) {
			return "firebase:" + identity.uid();
		}
		return "ip:" + clientAddress(request);
	}

	private boolean shouldApplyRateLimit(String path) {
		for (String pattern : RATE_LIMITED_PATHS) {
			if (path.startsWith(pattern)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * CloudFront appends the address it saw to whatever X-Forwarded-For the caller sent, so only the last
	 * entry is ours; the first, and X-Real-IP, are whatever the caller chose to write.
	 */
	// ponytail: trusts exactly one proxy hop (CloudFront). If 8080 is ever reachable directly, or a load
	// balancer is added in front, this must key on getRemoteAddr() or skip that many trailing entries.
	static String clientAddress(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			String[] hops = forwardedFor.split(",");
			return hops[hops.length - 1].trim();
		}
		return request.getRemoteAddr();
	}
}
