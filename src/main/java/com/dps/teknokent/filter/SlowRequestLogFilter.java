package com.dps.teknokent.filter;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * The app logs only warnings, so without this an overloaded backend leaves nothing in the logs. First in the
 * chain, so the time includes authentication. The path is logged without its query string.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SlowRequestLogFilter extends OncePerRequestFilter {

	private final long thresholdMs;

	public SlowRequestLogFilter(@Value("${app.slow-request-ms:2000}") long thresholdMs) {
		this.thresholdMs = thresholdMs;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		long start = System.nanoTime();
		try {
			filterChain.doFilter(request, response);
		} finally {
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			if (elapsedMs >= thresholdMs) {
				log.warn("Slow request: {} {} -> {} in {} ms",
						request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
			}
		}
	}
}
