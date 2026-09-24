package com.dps.teknokent.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.PushToken;
import com.dps.teknokent.repository.PushTokenRepository;
import com.dps.teknokent.util.TextUtils;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PushTokenService {

	private static final int USER_AGENT_LIMIT = 500;

	private final PushTokenRepository pushTokenRepository;
	private final DomainLookupService domainLookupService;

	// A token belongs to one browser; when another account signs in there, it moves to that account.
	@Transactional
	public void register(UUID userId, String token, String userAgent) {
		String trimmedToken = TextUtils.trim(token);
		PushToken pushToken = pushTokenRepository.findByToken(trimmedToken).orElseGet(() -> {
			PushToken created = new PushToken();
			created.setId(UUID.randomUUID());
			created.setToken(trimmedToken);
			return created;
		});
		pushToken.setUser(domainLookupService.requireUser(userId));
		pushToken.setUserAgent(truncate(TextUtils.normalizeOptional(userAgent)));
		pushToken.setLastSeenAt(Instant.now());
		pushTokenRepository.save(pushToken);
	}

	@Transactional
	public void unregister(UUID userId, String token) {
		pushTokenRepository.deleteByTokenAndUserId(TextUtils.trim(token), userId);
	}

	private static String truncate(String value) {
		return value == null || value.length() <= USER_AGENT_LIMIT ? value : value.substring(0, USER_AGENT_LIMIT);
	}
}
