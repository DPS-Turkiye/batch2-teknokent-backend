package com.dps.teknokent.config;

import java.util.Map;
import java.util.UUID;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import com.dps.teknokent.service.MeetingRealtimePublisher;
import com.dps.teknokent.service.MeetingRoomAccessService;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MeetingRoomSubscriptionInterceptor implements ChannelInterceptor {

	private final MeetingRoomAccessService meetingRoomAccessService;

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		if (!StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
			return message;
		}

		String destination = accessor.getDestination();
		UUID meetingId = meetingIdOf(destination);
		RealtimeIdentity identity = identityOf(accessor.getSessionAttributes());
		if (meetingId == null || identity == null
				|| !meetingRoomAccessService.isMeetingVisibleTo(meetingId, identity.organizationId())) {
			throw new MessagingException("Subscription to " + destination + " is not allowed.");
		}

		return message;
	}

	private UUID meetingIdOf(String destination) {
		if (destination == null || !destination.startsWith(MeetingRealtimePublisher.MEETING_TOPIC_PREFIX)) {
			return null;
		}
		try {
			return UUID.fromString(destination.substring(MeetingRealtimePublisher.MEETING_TOPIC_PREFIX.length()));
		} catch (IllegalArgumentException error) {
			return null;
		}
	}

	private RealtimeIdentity identityOf(Map<String, Object> sessionAttributes) {
		if (sessionAttributes == null) {
			return null;
		}
		Object identity = sessionAttributes.get(RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE);
		return identity instanceof RealtimeIdentity realtimeIdentity ? realtimeIdentity : null;
	}
}
