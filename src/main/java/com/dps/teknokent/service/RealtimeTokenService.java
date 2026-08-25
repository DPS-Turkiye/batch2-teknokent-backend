package com.dps.teknokent.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.dto.responses.RealtimeTokenResponse;
import com.dps.teknokent.exception.InvalidRequestException;

@Service
public class RealtimeTokenService {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	public record RealtimeIdentity(UUID userId, UUID organizationId) {
	}

	@Value("${app.realtime.token-secret:}")
	private String tokenSecret;
	@Value("${app.realtime.token-ttl-seconds:60}")
	private long tokenTtlSeconds;

	public RealtimeTokenResponse issue(UUID userId, UUID organizationId) {
		Instant expiresAt = Instant.now().plusSeconds(tokenTtlSeconds);
		String payload = userId + ":" + organizationId + ":" + expiresAt.getEpochSecond();
		String token = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + sign(payload);
		return new RealtimeTokenResponse(token, expiresAt);
	}

	public RealtimeIdentity verify(String token) {
		if (token == null || token.isBlank()) {
			return null;
		}
		int separator = token.lastIndexOf('.');
		if (separator <= 0) {
			return null;
		}

		String payload;
		try {
			payload = new String(DECODER.decode(token.substring(0, separator)), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException error) {
			return null;
		}

		String signature = token.substring(separator + 1);
		if (!MessageDigest.isEqual(
				sign(payload).getBytes(StandardCharsets.UTF_8),
				signature.getBytes(StandardCharsets.UTF_8))) {
			return null;
		}

		String[] parts = payload.split(":");
		if (parts.length != 3) {
			return null;
		}
		try {
			if (Instant.ofEpochSecond(Long.parseLong(parts[2])).isBefore(Instant.now())) {
				return null;
			}
			return new RealtimeIdentity(UUID.fromString(parts[0]), UUID.fromString(parts[1]));
		} catch (IllegalArgumentException error) {
			return null;
		}
	}

	private String sign(String payload) {
		if (tokenSecret == null || tokenSecret.isBlank()) {
			throw new InvalidRequestException("Realtime token secret is not configured.");
		}
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(tokenSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.GeneralSecurityException error) {
			throw new IllegalStateException("Realtime token could not be signed.", error);
		}
	}
}
