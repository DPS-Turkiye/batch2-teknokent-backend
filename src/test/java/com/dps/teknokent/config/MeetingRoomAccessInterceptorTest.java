package com.dps.teknokent.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeetingRoomAccessInterceptorTest {

	@Mock
	private MeetingRoomAccessService meetingRoomAccessService;

	private final UUID meetingId = UUID.fromString("00000000-0000-0000-0000-000000000301");
	private final UUID organizationId = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private final UUID userId = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private final Instant connectedAt = Instant.parse("2026-11-08T17:00:00Z");
	private final MutableClock clock = new MutableClock(connectedAt.plusSeconds(1));

	@Test
	void acceptsDocumentUpdatesForAVisibleMeeting() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		stillMember(true);
		MeetingRoomAccessInterceptor interceptor = interceptor();

		Message<?> message = documentUpdate(sessionAttributes());

		assertThat(interceptor.preSend(message, null)).isSameAs(message);
	}

	@Test
	void rejectsDocumentUpdatesForMeetingsOfAnotherOrganization() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(false);
		stillMember(true);
		MeetingRoomAccessInterceptor interceptor = interceptor();

		Message<?> message = documentUpdate(sessionAttributes());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	@Test
	void rejectsDocumentUpdatesWithoutARealtimeIdentity() {
		MeetingRoomAccessInterceptor interceptor = interceptor();

		Message<?> message = documentUpdate(new HashMap<>());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	@Test
	void looksUpMeetingVisibilityOncePerWebsocketSession() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		stillMember(true);
		MeetingRoomAccessInterceptor interceptor = interceptor();
		Map<String, Object> sessionAttributes = sessionAttributes();

		interceptor.preSend(subscribe(sessionAttributes), null);
		interceptor.preSend(documentUpdate(sessionAttributes), null);
		interceptor.preSend(documentUpdate(sessionAttributes), null);

		verify(meetingRoomAccessService, times(1)).isMeetingVisibleTo(meetingId, organizationId);
	}

	@Test
	void acceptsPresenceUpdatesForAVisibleMeeting() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		stillMember(true);
		MeetingRoomAccessInterceptor interceptor = interceptor();

		Message<?> message = presenceUpdate(sessionAttributes());

		assertThat(interceptor.preSend(message, null)).isSameAs(message);
	}

	@Test
	void rejectsPresenceUpdatesForMeetingsOfAnotherOrganization() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(false);
		stillMember(true);
		MeetingRoomAccessInterceptor interceptor = interceptor();

		Message<?> message = presenceUpdate(sessionAttributes());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	@Test
	void rejectsDestinationsOutsideTheMeetingRoom() {
		MeetingRoomAccessInterceptor interceptor = interceptor();

		Message<?> message = message(StompCommand.SEND, "/app/meetings/" + meetingId + "/todos", sessionAttributes());

		assertThatThrownBy(() -> interceptor.preSend(message, null))
				.isInstanceOf(MessagingException.class);
	}

	// A member who leaves, is rejected or has their sign-ins ended must not keep an open note.
	@Test
	void closesAnOpenRoomOnceTheAccountIsNoLongerAllowed() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		when(meetingRoomAccessService.isStillMemberOf(userId, organizationId, connectedAt))
				.thenReturn(true, false);
		MeetingRoomAccessInterceptor interceptor = interceptor();
		Map<String, Object> sessionAttributes = sessionAttributes();

		interceptor.preSend(subscribe(sessionAttributes), null);
		clock.advance(MeetingRoomAccessInterceptor.ACCOUNT_RECHECK);

		assertThatThrownBy(() -> interceptor.preSend(presenceUpdate(sessionAttributes), null))
				.isInstanceOf(MessagingException.class);
		assertThatThrownBy(() -> interceptor.preSend(documentUpdate(sessionAttributes), null))
				.isInstanceOf(MessagingException.class);
	}

	@Test
	void asksAboutTheAccountAtMostOncePerRecheckInterval() {
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		stillMember(true);
		MeetingRoomAccessInterceptor interceptor = interceptor();
		Map<String, Object> sessionAttributes = sessionAttributes();

		interceptor.preSend(subscribe(sessionAttributes), null);
		clock.advance(MeetingRoomAccessInterceptor.ACCOUNT_RECHECK.minusSeconds(1));
		interceptor.preSend(documentUpdate(sessionAttributes), null);
		verify(meetingRoomAccessService, times(1)).isStillMemberOf(userId, organizationId, connectedAt);

		clock.advance(Duration.ofSeconds(1));
		interceptor.preSend(documentUpdate(sessionAttributes), null);
		verify(meetingRoomAccessService, times(2)).isStillMemberOf(userId, organizationId, connectedAt);
	}

	// The browser reconnects within seconds of being closed, so the new socket starts after the revoke.
	@Test
	void judgesARevokeAgainstTheSignInRatherThanTheSocket() {
		Instant signedInAt = connectedAt.minusSeconds(600);
		when(meetingRoomAccessService.isMeetingVisibleTo(meetingId, organizationId)).thenReturn(true);
		when(meetingRoomAccessService.isStillMemberOf(userId, organizationId, signedInAt)).thenReturn(true);
		Map<String, Object> sessionAttributes = sessionAttributes();
		sessionAttributes.put(
				RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE,
				new RealtimeIdentity(userId, organizationId, signedInAt));

		interceptor().preSend(subscribe(sessionAttributes), null);

		verify(meetingRoomAccessService).isStillMemberOf(userId, organizationId, signedInAt);
	}

	@Test
	void rejectsASessionWithoutAConnectTime() {
		MeetingRoomAccessInterceptor interceptor = interceptor();
		Map<String, Object> sessionAttributes = sessionAttributes();
		sessionAttributes.remove(RealtimeHandshakeInterceptor.CONNECTED_AT_ATTRIBUTE);

		assertThatThrownBy(() -> interceptor.preSend(subscribe(sessionAttributes), null))
				.isInstanceOf(MessagingException.class);
		verifyNoInteractions(meetingRoomAccessService);
	}

	private MeetingRoomAccessInterceptor interceptor() {
		return new MeetingRoomAccessInterceptor(meetingRoomAccessService, clock);
	}

	private void stillMember(boolean allowed) {
		when(meetingRoomAccessService.isStillMemberOf(userId, organizationId, connectedAt)).thenReturn(allowed);
	}

	private Map<String, Object> sessionAttributes() {
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(
				RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE,
				new RealtimeIdentity(userId, organizationId, null));
		attributes.put(RealtimeHandshakeInterceptor.CONNECTED_AT_ATTRIBUTE, connectedAt);
		return attributes;
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	private Message<?> subscribe(Map<String, Object> sessionAttributes) {
		return message(StompCommand.SUBSCRIBE, "/topic/meetings/" + meetingId, sessionAttributes);
	}

	private Message<?> documentUpdate(Map<String, Object> sessionAttributes) {
		return message(StompCommand.SEND, "/app/meetings/" + meetingId + "/document", sessionAttributes);
	}

	private Message<?> presenceUpdate(Map<String, Object> sessionAttributes) {
		return message(StompCommand.SEND, "/app/meetings/" + meetingId + "/presence", sessionAttributes);
	}

	private Message<?> message(StompCommand command, String destination, Map<String, Object> sessionAttributes) {
		StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
		accessor.setDestination(destination);
		accessor.setSessionAttributes(sessionAttributes);
		accessor.setLeaveMutable(true);
		return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
	}
}
