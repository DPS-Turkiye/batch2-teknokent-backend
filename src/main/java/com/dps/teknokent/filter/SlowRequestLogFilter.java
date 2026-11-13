package com.dps.teknokent.filter;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * The app logs only warnings, so without this an overloaded backend leaves nothing in the logs. First in the
 * chain, so the time includes authentication. The path is logged without its query string.
 *
 * <p>A request whose body stops arriving gets its own line. Tomcat answers it with a 400 and Spring treats it as
 * a caller who went away, so nothing else is logged, and the request never reaches a controller: without this
 * line it reads as a slow endpoint that refused a file. The failure's class is named because a body Tomcat
 * could not make sense of, such as broken chunking, takes the same path.
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
			// Tomcat leaves the read failure here when it gives up on a request body.
			if (response.getStatus() == HttpServletResponse.SC_BAD_REQUEST
					&& request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof IOException cause) {
				long announced = request.getContentLengthLong();
				log.warn("Request body cut off: {} {} -> {} after {} ms ({}, {})",
						request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs,
						announced < 0 ? "length not announced" : announced + " bytes announced",
						cause.getClass().getSimpleName());
			} else if (elapsedMs >= thresholdMs) {
				log.warn("Slow request: {} {} -> {} in {} ms",
						request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs);
			}
		}
	}
}
