package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;

class FirebasePushSenderTest {

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
