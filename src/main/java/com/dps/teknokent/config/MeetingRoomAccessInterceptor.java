package com.dps.teknokent.config;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import com.dps.teknokent.domain.MeetingRoomDestinations;
import com.dps.teknokent.service.MeetingRoomAccessService;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MeetingRoomAccessInterceptor implements ChannelInterceptor {

	static final String ALLOWED_MEETINGS_ATTRIBUTE = "allowedMeetings";

	private final MeetingRoomAccessService meetingRoomAccessService;

	@Override
	public Message<?> preSend(Message<?> message, MessageChannel channel) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
		StompCommand command = accessor.getCommand();
		if (!StompCommand.SUBSCRIBE.equals(command) && !StompCommand.SEND.equals(command)) {
			return message;
		}

		String destination = accessor.getDestination();
		UUID meetingId = meetingIdOf(destination);
		Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
		RealtimeIdentity identity = identityOf(sessionAttributes);
		if (meetingId == null || identity == null || !isAllowed(meetingId, identity, sessionAttributes)) {
			throw new MessagingException("Access to " + destination + " is not allowed.");
		}

		return message;
	}

	private boolean isAllowed(UUID meetingId, RealtimeIdentity identity, Map<String, Object> sessionAttributes) {
		AllowedMeetings allowedMeetings = allowedMeetings(sessionAttributes);
		if (allowedMeetings.contains(meetingId)) {
			return true;
		}
		if (!meetingRoomAccessService.isMeetingVisibleTo(meetingId, identity.organizationId())) {
			return false;
		}
		allowedMeetings.allow(meetingId);
		return true;
	}

	private AllowedMeetings allowedMeetings(Map<String, Object> sessionAttributes) {
		synchronized (sessionAttributes) {
			Object cached = sessionAttributes.get(ALLOWED_MEETINGS_ATTRIBUTE);
			if (cached instanceof AllowedMeetings allowedMeetings) {
				return allowedMeetings;
			}
			AllowedMeetings allowedMeetings = new AllowedMeetings();
			sessionAttributes.put(ALLOWED_MEETINGS_ATTRIBUTE, allowedMeetings);
			return allowedMeetings;
		}
	}

	private UUID meetingIdOf(String destination) {
		if (destination == null) {
			return null;
		}

		String meetingId;
		if (destination.startsWith(MeetingRoomDestinations.TOPIC_PREFIX)) {
			meetingId = destination.substring(MeetingRoomDestinations.TOPIC_PREFIX.length());
		} else if (destination.startsWith(MeetingRoomDestinations.INBOUND_PREFIX)) {
			meetingId = inboundMeetingId(destination.substring(MeetingRoomDestinations.INBOUND_PREFIX.length()));
			if (meetingId == null) {
				return null;
			}
		} else {
			return null;
		}

		try {
			return UUID.fromString(meetingId);
		} catch (IllegalArgumentException error) {
			return null;
		}
	}

	private String inboundMeetingId(String path) {
		for (String suffix : MeetingRoomDestinations.INBOUND_SUFFIXES) {
			if (path.endsWith(suffix)) {
				return path.substring(0, path.length() - suffix.length());
			}
		}
		return null;
	}

	private RealtimeIdentity identityOf(Map<String, Object> sessionAttributes) {
		if (sessionAttributes == null) {
			return null;
		}
		Object identity = sessionAttributes.get(RealtimeHandshakeInterceptor.IDENTITY_ATTRIBUTE);
		return identity instanceof RealtimeIdentity realtimeIdentity ? realtimeIdentity : null;
	}

	private static final class AllowedMeetings {

		private final Set<UUID> meetingIds = ConcurrentHashMap.newKeySet();

		private boolean contains(UUID meetingId) {
			return meetingIds.contains(meetingId);
		}

		private void allow(UUID meetingId) {
			meetingIds.add(meetingId);
		}
	}
}
