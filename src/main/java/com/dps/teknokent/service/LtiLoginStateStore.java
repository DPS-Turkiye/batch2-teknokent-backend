package com.dps.teknokent.service;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * The OIDC {@code state} → {@code nonce} pairs of launches in flight. The browser's trip through Moodle is a
 * cross-site POST that drops lax cookies, so the pair lives here instead; reading it deletes it, so a state and
 * its nonce work once.
 */
@Component
@RequiredArgsConstructor
public class LtiLoginStateStore {

	private static final Duration TTL = Duration.ofMinutes(10);
	private static final String PREFIX = "lti:login:";

	private final StringRedisTemplate redis;

	public void save(String state, String nonce) {
		redis.opsForValue().set(PREFIX + state, nonce, TTL);
	}

	public Optional<String> consume(String state) {
		return Optional.ofNullable(redis.opsForValue().getAndDelete(PREFIX + state));
	}
}
