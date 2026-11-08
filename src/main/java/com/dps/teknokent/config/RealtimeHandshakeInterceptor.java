package com.dps.teknokent.config;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.dps.teknokent.service.RealtimeTokenService;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class RealtimeHandshakeInterceptor implements HandshakeInterceptor {

	public static final String IDENTITY_ATTRIBUTE = "realtimeIdentity";
	public static final String CONNECTED_AT_ATTRIBUTE = "realtimeConnectedAt";

	private final RealtimeTokenService realtimeTokenService;

	@Override
	public boolean beforeHandshake(
			ServerHttpRequest request,
			ServerHttpResponse response,
			WebSocketHandler handler,
			Map<String, Object> attributes) {
		String token = UriComponentsBuilder.fromUri(request.getURI()).build()
				.getQueryParams()
				.getFirst("token");
		RealtimeIdentity identity = realtimeTokenService.verify(token);
		if (identity == null) {
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return false;
		}

		attributes.put(IDENTITY_ATTRIBUTE, identity);
		attributes.put(CONNECTED_AT_ATTRIBUTE, Instant.now());
		return true;
	}

	@Override
	public void afterHandshake(
			ServerHttpRequest request,
			ServerHttpResponse response,
			WebSocketHandler handler,
			Exception exception) {
	}
}
