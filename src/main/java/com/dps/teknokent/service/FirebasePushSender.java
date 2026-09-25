package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.dps.teknokent.dto.provider.PushMessage;
import com.dps.teknokent.repository.PushTokenRepository;
import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushNotification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FirebasePushSender implements PushSender {

	private static final String ICON = "/icons/icon-192.png";
	private static final Set<MessagingErrorCode> DEAD_TOKEN_CODES =
			Set.of(MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT);

	private final FirebaseMessaging firebaseMessaging;
	private final PushTokenRepository pushTokenRepository;

	@Override
	public void send(List<String> tokens, PushMessage message) {
		ApiFutures.addCallback(
				firebaseMessaging.sendEachForMulticastAsync(toMulticast(tokens, message)),
				new ApiFutureCallback<>() {
					@Override
					public void onSuccess(BatchResponse response) {
						List<String> deadTokens = deadTokens(tokens, response);
						if (!deadTokens.isEmpty()) {
							pushTokenRepository.deleteByTokenIn(deadTokens);
						}
					}

					@Override
					public void onFailure(Throwable failure) {
						log.warn("Push delivery failed for notification {}", message.notificationId(), failure);
					}
				},
				MoreExecutors.directExecutor());
	}

	static MulticastMessage toMulticast(List<String> tokens, PushMessage message) {
		WebpushConfig.Builder webpush = WebpushConfig.builder()
				.setNotification(WebpushNotification.builder()
						.setTitle(message.title())
						.setBody(message.body())
						.setIcon(ICON)
						.setTag(message.notificationId().toString())
						.build())
				.putData("notificationId", message.notificationId().toString())
				.putData("category", message.category().name());
		if (message.href() != null) {
			webpush.putData("href", message.href());
		}
		return MulticastMessage.builder()
				.addAllTokens(tokens)
				.setWebpushConfig(webpush.build())
				.build();
	}

	// FCM answers per token in request order; only tokens it reports as gone are dropped.
	static List<String> deadTokens(List<String> tokens, BatchResponse response) {
		List<String> dead = new ArrayList<>();
		List<SendResponse> responses = response.getResponses();
		for (int index = 0; index < responses.size() && index < tokens.size(); index++) {
			FirebaseMessagingException exception = responses.get(index).getException();
			if (exception != null && DEAD_TOKEN_CODES.contains(exception.getMessagingErrorCode())) {
				dead.add(tokens.get(index));
			}
		}
		return dead;
	}
}
