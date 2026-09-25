package com.dps.teknokent.service;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.dps.teknokent.entity.PushToken;
import com.dps.teknokent.repository.PushTokenRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class PushNotificationDispatcher {

	private final NotificationPreferenceService notificationPreferenceService;
	private final PushTokenRepository pushTokenRepository;
	private final PushSender pushSender;

	// After commit, so a rolled-back action never pushes; a push failure never fails the action.
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onNotificationCreated(NotificationCreatedEvent event) {
		try {
			if (!notificationPreferenceService.allows(event.recipientId(), event.message().category())) {
				return;
			}
			List<String> tokens = pushTokenRepository.findByUserId(event.recipientId()).stream()
					.map(PushToken::getToken)
					.toList();
			if (!tokens.isEmpty()) {
				pushSender.send(tokens, event.message());
			}
		} catch (RuntimeException exception) {
			log.warn("Push dispatch failed for notification {}", event.message().notificationId(), exception);
		}
	}
}
