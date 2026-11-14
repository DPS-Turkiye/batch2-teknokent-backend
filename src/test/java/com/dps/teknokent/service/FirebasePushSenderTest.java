package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.dps.teknokent.domain.NotificationCategory;
import com.dps.teknokent.dto.provider.PushMessage;
import com.dps.teknokent.repository.PushTokenRepository;
import com.google.api.core.ApiFutures;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;

class FirebasePushSenderTest {

	private static final UUID NOTIFICATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	private final FirebaseMessaging firebaseMessaging = mock(FirebaseMessaging.class);
	private final PushTokenRepository pushTokenRepository = mock(PushTokenRepository.class);
	private final FirebasePushSender sender = new FirebasePushSender(firebaseMessaging, pushTokenRepository);

	@Test
	void dropsOnlyTokensFcmReportsAsGone() {
		List<SendResponse> responses = List.of(
				delivered(),
				failed(MessagingErrorCode.UNREGISTERED),
				failed(MessagingErrorCode.UNAVAILABLE),
				failed(MessagingErrorCode.INVALID_ARGUMENT));
		BatchResponse response = mock(BatchResponse.class);
		when(response.getResponses()).thenReturn(responses);

		List<String> dead = FirebasePushSender.deadTokens(List.of("ok", "gone", "busy", "garbled"), response);

		assertThat(dead).containsExactly("gone", "garbled");
	}

	@Test
	void forgetsTheTokensFirebaseReportsAsGoneOnceTheSendComesBack() {
		List<SendResponse> responses = List.of(delivered(), failed(MessagingErrorCode.UNREGISTERED),
				failed(MessagingErrorCode.QUOTA_EXCEEDED));
		BatchResponse batch = mock(BatchResponse.class);
		when(batch.getResponses()).thenReturn(responses);
		when(firebaseMessaging.sendEachForMulticastAsync(any(MulticastMessage.class)))
				.thenReturn(ApiFutures.immediateFuture(batch));

		sender.send(List.of("phone", "old-laptop", "tablet"), message("/messages/42"));

		verify(pushTokenRepository).deleteByTokenIn(List.of("old-laptop"));
		ArgumentCaptor<MulticastMessage> sent = ArgumentCaptor.forClass(MulticastMessage.class);
		verify(firebaseMessaging).sendEachForMulticastAsync(sent.capture());
		assertThat(sdkField(sent.getValue(), "tokens")).asInstanceOf(InstanceOfAssertFactories.list(String.class))
				.containsExactly("phone", "old-laptop", "tablet");
	}

	@Test
	void keepsEveryTokenWhenFirebaseReportsNoneAsGone() {
		List<SendResponse> responses = List.of(delivered(), failed(MessagingErrorCode.UNAVAILABLE));
		BatchResponse batch = mock(BatchResponse.class);
		when(batch.getResponses()).thenReturn(responses);
		when(firebaseMessaging.sendEachForMulticastAsync(any(MulticastMessage.class)))
				.thenReturn(ApiFutures.immediateFuture(batch));

		sender.send(List.of("phone", "tablet"), message("/messages/42"));

		verifyNoInteractions(pushTokenRepository);
	}

	// A push is a courtesy on top of the stored notification, so a Firebase outage must not fail the caller.
	@Test
	void aSendFirebaseCouldNotMakeIsSwallowedAndForgetsNoToken() {
		when(firebaseMessaging.sendEachForMulticastAsync(any(MulticastMessage.class)))
				.thenReturn(ApiFutures.immediateFailedFuture(new IllegalStateException("FCM is down")));

		sender.send(List.of("phone"), message("/messages/42"));

		verifyNoInteractions(pushTokenRepository);
	}

	@Test
	void theWebPushShowsTheNotificationTextAndCarriesWhereItLeads() {
		MulticastMessage multicast = FirebasePushSender.toMulticast(List.of("phone", "tablet"),
				message("/messages/42"));

		assertThat(sdkField(multicast, "tokens")).asInstanceOf(InstanceOfAssertFactories.list(String.class))
				.containsExactly("phone", "tablet");
		Object webpush = sdkField(multicast, "webpushConfig");
		assertThat(sdkField(webpush, "notification"))
				.asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
				.containsOnly(
						entry("title", "Yeni mesaj"),
						entry("body", "Ayşe size yazdı"),
						entry("icon", "/icons/icon-192.png"),
						// The tag lets a repeat of the same notification replace the one already shown.
						entry("tag", NOTIFICATION_ID.toString()));
		assertThat(sdkField(webpush, "data"))
				.asInstanceOf(InstanceOfAssertFactories.map(String.class, String.class))
				.containsOnly(
						entry("notificationId", NOTIFICATION_ID.toString()),
						entry("category", "MESSAGE"),
						entry("href", "/messages/42"));
	}

	@Test
	void aNotificationThatLeadsNowhereCarriesNoLink() {
		MulticastMessage multicast = FirebasePushSender.toMulticast(List.of("phone"), message(null));

		assertThat(sdkField(sdkField(multicast, "webpushConfig"), "data"))
				.asInstanceOf(InstanceOfAssertFactories.map(String.class, String.class))
				.containsOnlyKeys("notificationId", "category");
	}

	private static PushMessage message(String href) {
		return new PushMessage(NOTIFICATION_ID, NotificationCategory.MESSAGE, "Yeni mesaj", "Ayşe size yazdı", href);
	}

	// The SDK's message classes have no public getters; what they will send is only visible in their fields.
	private static Object sdkField(Object sdkObject, String name) {
		return ReflectionTestUtils.getField(sdkObject, name);
	}

	private static SendResponse delivered() {
		SendResponse response = mock(SendResponse.class);
		when(response.getException()).thenReturn(null);
		return response;
	}

	private static SendResponse failed(MessagingErrorCode code) {
		FirebaseMessagingException exception = mock(FirebaseMessagingException.class);
		when(exception.getMessagingErrorCode()).thenReturn(code);
		SendResponse response = mock(SendResponse.class);
		when(response.getException()).thenReturn(exception);
		return response;
	}
}
