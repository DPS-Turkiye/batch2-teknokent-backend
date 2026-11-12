package com.dps.teknokent.filter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.dps.teknokent.domain.Edition;
import com.dps.teknokent.dto.responses.ApiErrorResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers 404 for every path that belongs to the other edition, before authentication runs, so the closed
 * endpoints look the same as ones that do not exist, to signed-in and anonymous callers alike. It sits inside the
 * security chain (not as a plain servlet filter) so Spring Security's firewall has already rejected encoded or
 * non-normalized paths that could slip past the patterns.
 */
public class EditionPathFilter extends OncePerRequestFilter {

	private final List<RequestMatcher> closedPaths;
	private final JsonMapper jsonMapper;

	public EditionPathFilter(Edition edition, JsonMapper jsonMapper) {
		this.closedPaths = edition.closedPaths().stream()
				.<RequestMatcher>map(PathPatternRequestMatcher::pathPattern)
				.toList();
		this.jsonMapper = jsonMapper;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (closedPaths.stream().noneMatch(matcher -> matcher.matches(request))) {
			chain.doFilter(request, response);
			return;
		}
		HttpStatus status = HttpStatus.NOT_FOUND;
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), new ApiErrorResponse(
				Instant.now(), status.value(), status.getReasonPhrase(), "Not found", request.getRequestURI(),
				Map.of()));
	}
}
