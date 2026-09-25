package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.provider.PushMessage;
import com.dps.teknokent.entity.PushToken;
import com.dps.teknokent.repository.PushTokenRepository;

@ExtendWith(MockitoExtension.class)
class PushNotificationDispatcherTest {

	private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final PushMessage MESSAGE = new PushMessage(
			UUID.randomUUID(), NotificationCategory.MESSAGE, "Yeni mesaj", "Merhaba", "/mentorships");

	@Mock
	private NotificationPreferenceService notificationPreferenceService;

	@Mock
	private PushTokenRepository pushTokenRepository;

	@Mock
	private PushSender pushSender;

	@InjectMocks
	private PushNotificationDispatcher dispatcher;

	@Test
	void pushesToEveryBrowserTheRecipientRegistered() {
		when(notificationPreferenceService.allows(USER_ID, NotificationCategory.MESSAGE)).thenReturn(true);
		when(pushTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(token("phone"), token("laptop")));

		dispatcher.onNotificationCreated(new NotificationCreatedEvent(USER_ID, MESSAGE));

		verify(pushSender).send(List.of("phone", "laptop"), MESSAGE);
	}

	@Test
	void skipsACategoryTheRecipientMuted() {
		when(notificationPreferenceService.allows(USER_ID, NotificationCategory.MESSAGE)).thenReturn(false);

		dispatcher.onNotificationCreated(new NotificationCreatedEvent(USER_ID, MESSAGE));

		verifyNoInteractions(pushTokenRepository, pushSender);
	}

	@Test
	void skipsARecipientWithoutRegisteredBrowsers() {
		when(notificationPreferenceService.allows(USER_ID, NotificationCategory.MESSAGE)).thenReturn(true);
		when(pushTokenRepository.findByUserId(USER_ID)).thenReturn(List.of());

		dispatcher.onNotificationCreated(new NotificationCreatedEvent(USER_ID, MESSAGE));

		verify(pushSender, never()).send(any(), any());
	}

	@Test
	void aFailingPushNeverReachesTheCaller() {
		when(notificationPreferenceService.allows(USER_ID, NotificationCategory.MESSAGE)).thenReturn(true);
		when(pushTokenRepository.findByUserId(USER_ID)).thenReturn(List.of(token("phone")));
		doThrow(new IllegalStateException("FCM down")).when(pushSender).send(any(), any());

		assertThatCode(() -> dispatcher.onNotificationCreated(new NotificationCreatedEvent(USER_ID, MESSAGE)))
				.doesNotThrowAnyException();
	}

	private static PushToken token(String value) {
		PushToken token = new PushToken();
		token.setToken(value);
		return token;
	}
}
