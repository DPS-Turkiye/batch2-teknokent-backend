package com.dps.teknokent.service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MeetingNoteDocumentStore {

	private static final String DOCUMENT_KEY_PREFIX = "meeting-note:";
	private static final String SEED_LOCK_KEY_SUFFIX = ":seed";

	private final StringRedisTemplate redis;

	@Value("${app.realtime.document-ttl-hours:12}")
	private long documentTtlHours;
	@Value("${app.realtime.seed-lock-seconds:30}")
	private long seedLockSeconds;

	public Optional<String> findState(UUID meetingId) {
		return Optional.ofNullable(redis.opsForValue().get(documentKey(meetingId)));
	}

	public void saveState(UUID meetingId, String encodedState) {
		redis.opsForValue().set(documentKey(meetingId), encodedState, Duration.ofHours(documentTtlHours));
	}

	public boolean claimSeed(UUID meetingId) {
		return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(
				documentKey(meetingId) + SEED_LOCK_KEY_SUFFIX,
				meetingId.toString(),
				Duration.ofSeconds(seedLockSeconds)));
	}

	private String documentKey(UUID meetingId) {
		return DOCUMENT_KEY_PREFIX + meetingId;
	}
}
