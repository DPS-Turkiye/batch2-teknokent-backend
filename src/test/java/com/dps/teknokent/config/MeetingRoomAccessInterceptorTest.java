package com.dps.teknokent.config;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import com.dps.teknokent.service.MeetingRoomAccessService;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingRoomAccessInterceptorTest {

	@Mock
	private MeetingRoomAccessService meetingRoomAccessService;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");

	@Test
	void acceptsDocumentUpdatesForAVisibleMeeting() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		MeetingRoomAccessInterceptor interceptor = new MeetingRoomAccessInterceptor(meetingRoomAccessService);

		Message<?> message = documentUpdate(sessionAttributes());

		assertThat(interceptor.preSend(message, null)).isSameAs(message);
	}

	@Test
	void rejectsDocumentUpdatesForMeetingsOfAnotherOrganization() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(false);
		MeetingRoomAccessInterceptor interceptor = new MeetingRoomAccessInterceptor(meetingRoomAccessService);

		Message<?> message = documentUpdate(sessionAttributes());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	@Test
	void rejectsDocumentUpdatesWithoutARealtimeIdentity() {
		MeetingRoomAccessInterceptor interceptor = new MeetingRoomAccessInterceptor(meetingRoomAccessService);

		Message<?> message = documentUpdate(new HashMap<>());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	@Test
	void looksUpMeetingVisibilityOncePerWebsocketSession() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		MeetingRoomAccessInterceptor interceptor = new MeetingRoomAccessInterceptor(meetingRoomAccessService);
		Map<String, Object> sessionAttributes = sessionAttributes();

		interceptor.preSend(subscribe(sessionAttributes), null);
		interceptor.preSend(documentUpdate(sessionAttributes), null);
		interceptor.preSend(documentUpdate(sessionAttributes), null);

		verify(meetingRoomAccessService, times(1)).isMeetingVisibleTo(meetingId, organizationId);
	}

	@Test
	void rejectsDestinationsOutsideTheMeetingRoom() {
		MeetingRoomAccessInterceptor interceptor = new MeetingRoomAccessInterceptor(meetingRoomAccessService);

		Message<?> message = message(StompCommand.SEND, "/app/meetings/" + meetingId + "/todos", sessionAttributes());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	private Map<String, Object> sessionAttributes() {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(
				RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE,
				new RealtimeIdentity(userId, organizationId));
		return attributes;
	}

	private Message<?> subscribe(Map<String, Object> sessionAttributes) {
		return message(StompCommand.SUBSCRIBE, "/topic/meetings/" + meetingId, sessionAttributes);
	}

	private Message<?> documentUpdate(Map<String, Object> sessionAttributes) {
		return message(StompCommand.SEND, "/app/meetings/" + meetingId + "/document", sessionAttributes);
	}

	private Message<?> message(StompCommand command, String destination, Map<String, Object> sessionAttributes) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
		accessor.setDestination(destination);
		accessor.setSessionAttributes(sessionAttributes);
		accessor.setLeaveMutable(true);
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}
}
