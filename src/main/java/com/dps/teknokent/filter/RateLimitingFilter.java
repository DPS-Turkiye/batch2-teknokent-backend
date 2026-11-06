package com.dps.teknokent.filter;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.config.RateLimitingConfig.TokenBucket;
import com.dps.teknokent.security.AppUserPrincipal;
import com.dps.teknokent.security.FirebaseIdentity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.io.IOException;

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

	/**
	 * Each CV or syllabus parse runs OCR and a paid LLM call, so it gets a far tighter budget than the general limit.
	 */
	private static final long CV_PARSE_CAPACITY = 10;
	private static final long CV_PARSE_WINDOW_MS = 10 * 60_000;

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String path = request.getRequestURI();
		TokenBucket bucket;
		if (isCvParse(request)) {
			bucket = rateLimitingConfig.resolveBucket(
					"cv_parse:" + getCallerIdentifier(request), CV_PARSE_CAPACITY, CV_PARSE_WINDOW_MS);
		} else if (shouldApplyRateLimit(path)) {
			bucket = rateLimitingConfig.resolveBucket(getClientIdentifier(request));
		} else {
			filterChain.doFilter(request, response);
			return;
		}

		if (bucket.tryConsume(1)) {
			response.addHeader("X-Rate-Limit-Remaining", String.valueOf(bucket.getRemainingTokens()));
			filterChain.doFilter(request, response);
		} else {
			long secondsToWait = bucket.getSecondsToWait();
			response.addHeader(RETRY_AFTER_HEADER, String.valueOf(secondsToWait));
			response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), 
					"Too many requests. Please retry after " + secondsToWait + " seconds.");
		}
	}

	private static boolean isCvParse(HttpServletRequest request) {
		String path = request.getRequestURI();
		return "POST".equalsIgnoreCase(request.getMethod())
				&& (CV_PARSE_PATH.equals(path)
						|| CV_PREVIEW_PATH.equals(path)
						|| path.startsWith(CV_PREVIEW_PATH + "/documents/")
						|| SYLLABUS_PARSE_PATH.equals(path));
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
		return "ip:" + getClientIdentifier(request);
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
	private String getClientIdentifier(HttpServletRequest request) {
		String forwardedFor = request.getHeader("X-Forwarded-For");
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			String[] hops = forwardedFor.split(",");
			return hops[hops.length - 1].trim();
		}
		return request.getRemoteAddr();
	}
}


