package com.dps.teknokent.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;

import com.dps.teknokent.domain.MeetingRoomDestinations;
import com.dps.teknokent.service.MeetingRoomAccessService;
import com.dps.teknokent.service.RealtimeTokenService.RealtimeIdentity;

public class MeetingRoomAccessInterceptor implements ChannelInterceptor {

	static final String ALLOWED_MEETINGS_ATTRIBUTE = "allowedMeetings";
	static final String ACCOUNT_CHECK_ATTRIBUTE = "accountCheck";

	/**
	 * A socket outlives the session check that issued its token, so the account is asked again this often: leaving
	 * the startup, a rejected account or an admin's "end all sign-ins" closes an open note within about a minute.
	 * The app's own client sends a presence update every 15 s, so even an idle note is caught.
	 */
	static final Duration ACCOUNT_RECHECK = Duration.ofSeconds(60);

	private final MeetingRoomAccessService meetingRoomAccessService;
	private final Clock clock;

	public MeetingRoomAccessInterceptor(MeetingRoomAccessService meetingRoomAccessService) {
		this(meetingRoomAccessService, Clock.systemUTC());
	}

	MeetingRoomAccessInterceptor(MeetingRoomAccessService meetingRoomAccessService, Clock clock) {
		this.meetingRoomAccessService = meetingRoomAccessService;
		this.clock = clock;
	}

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
		if (meetingId == null
				|| identity == null
				|| !isAccountStillAllowed(identity, sessionAttributes)
				|| !isAllowed(meetingId, identity, sessionAttributes)) {
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

	private boolean isAccountStillAllowed(RealtimeIdentity identity, Map<String, Object> sessionAttributes) {
		Instant now = clock.instant();
		AccountCheck accountCheck = sessionValue(sessionAttributes, ACCOUNT_CHECK_ATTRIBUTE, AccountCheck.class,
				AccountCheck::new);
		if (accountCheck.isFresh(now)) {
			return true;
		}
		Instant signedInAt = signedInAt(identity, sessionAttributes);
		if (signedInAt == null
				|| !meetingRoomAccessService.isStillMemberOf(identity.userId(), identity.organizationId(), signedInAt)) {
			return false;
		}
		accountCheck.passed(now);
		return true;
	}

	/**
	 * The sign-in the token was issued for. A reconnect opens a new socket after a revoke, so the socket's own start
	 * would let a revoked sign-in back in; it is only the fallback for a token that does not carry the sign-in.
	 */
	private static Instant signedInAt(RealtimeIdentity identity, Map<String, Object> sessionAttributes) {
		if (identity.signedInAt() != null) {
			return identity.signedInAt();
		}
		return sessionAttributes.get(RealtimeHandshakeInterceptor.CONNECTED_AT_ATTRIBUTE) instanceof Instant connectedAt
				? connectedAt
				: null;
	}

	private AllowedMeetings allowedMeetings(Map<String, Object> sessionAttributes) {
		return sessionValue(sessionAttributes, ALLOWED_MEETINGS_ATTRIBUTE, AllowedMeetings.class, AllowedMeetings::new);
	}

	private static <T> T sessionValue(
			Map<String, Object> sessionAttributes, String key, Class<T> type, Supplier<T> create) {
		synchronized (sessionAttributes) {
			Object cached = sessionAttributes.get(key);
			if (type.isInstance(cached)) {
				return type.cast(cached);
			}
			T value = create.get();
			sessionAttributes.put(key, value);
			return value;
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

	private static final class AccountCheck {

		private volatile Instant passedAt;

		private boolean isFresh(Instant now) {
			Instant last = passedAt;
			return last != null && now.isBefore(last.plus(ACCOUNT_RECHECK));
		}

		private void passed(Instant now) {
			passedAt = now;
		}
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
