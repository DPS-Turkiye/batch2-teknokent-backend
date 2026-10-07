package com.dps.teknokent.filter;

import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.dps.teknokent.config.RateLimitingConfig;
import com.dps.teknokent.config.RateLimitingConfig.TokenBucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.io.IOException;

@Component
@ConditionalOnProperty(name = "rate-limiting.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

	private final RateLimitingConfig rateLimitingConfig;

	private static final String[] RATE_LIMITED_PATHS = {
			"/api/assistant/",
	};

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		String path = request.getRequestURI();
		boolean shouldRateLimit = shouldApplyRateLimit(path);

		if (!shouldRateLimit) {
			filterChain.doFilter(request, response);
			return;
		}

		String clientKey = getClientIdentifier(request);
		TokenBucket bucket = rateLimitingConfig.resolveBucket(clientKey);

		if (bucket.tryConsume(1)) {
			response.addHeader("X-Rate-Limit-Remaining", String.valueOf(bucket.getRemainingTokens()));
			filterChain.doFilter(request, response);
		} else {
			long secondsToWait = bucket.getSecondsToWait();
			response.addHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(secondsToWait));
			response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), 
					"Too many requests. Please retry after " + secondsToWait + " seconds.");
		}
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


