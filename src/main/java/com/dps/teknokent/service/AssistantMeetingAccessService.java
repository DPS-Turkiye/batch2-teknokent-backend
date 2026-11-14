package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.TranscriptBotConstants;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;

/**
 * Who may use the meeting assistant (bot, live transcript, report) for a Meet code: an admin, or a participant of the
 * session the provider's bot is on, meaning the mentor or a member of the team of a meeting in it. The code is all the
 * provider knows, and mentors reuse one personal room for every team, so the session is the latest start on that code
 * that has begun (or is about to, by the bot's join lead), together with every copy of it (a guest team's, another
 * spelling of the link). Before the room's first meeting starts, that first meeting is the session.
 */
@Service
public class AssistantMeetingAccessService {

	private final MeetingRepository meetingRepository;
	private final AppUserRepository appUserRepository;
	private final Clock clock;

	@Autowired
	public AssistantMeetingAccessService(MeetingRepository meetingRepository, AppUserRepository appUserRepository) {
		this(meetingRepository, appUserRepository, Clock.systemUTC());
	}

	AssistantMeetingAccessService(MeetingRepository meetingRepository, AppUserRepository appUserRepository,
			Clock clock) {
		this.meetingRepository = meetingRepository;
		this.appUserRepository = appUserRepository;
		this.clock = clock;
	}

	/**
	 * Lets only an admin or a participant of the meeting (its mentor or a member of its team) use its assistant.
	 *
	 * @param callerId the signed-in user from a verified bearer token; null when the request came on the shared
	 *                 service credentials, which name nobody and so are refused
	 * @return the platform and code as checked, for the provider call that follows
	 */
	@Transactional(readOnly = true)
	public MeetingUrlParser.Parsed requireAccess(@Nullable UUID callerId, @Nullable String platform,
			String nativeMeetingId) {
		if (callerId == null) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A signed-in user is required.");
		}
		MeetingUrlParser.Parsed parsed = parse(platform, nativeMeetingId);
		if (parsed == null) {
			throw new ResourceNotFoundException("Meeting not found.");
		}
		List<Meeting> meetings = meetingsOn(parsed.nativeMeetingId());
		if (meetings.isEmpty()) {
			throw new ResourceNotFoundException("Meeting not found.");
		}
		AppUser caller = appUserRepository.findById(callerId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "A signed-in user is required."));
		if (caller.has(UserRole.ADMIN)) {
			return parsed;
		}
		UUID callerOrganizationId = caller.getOrganization() == null ? null : caller.getOrganization().getId();
		boolean participant = currentSession(meetings).stream()
				.anyMatch(meeting -> isMentor(meeting, callerId) || isTeamMember(meeting, callerOrganizationId));
		if (!participant) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN,
					"Only a participant of this meeting can use its assistant.");
		}
		return parsed;
	}

	private static MeetingUrlParser.@Nullable Parsed parse(@Nullable String platform, String nativeMeetingId) {
		MeetingUrlParser.Parsed parsed = MeetingUrlParser.tryParse(nativeMeetingId).orElse(null);
		if (parsed == null || platform == null
				|| !parsed.platform().equals(platform.trim().toLowerCase(Locale.ROOT))) {
			return null;
		}
		return parsed;
	}

	private List<Meeting> meetingsOn(String code) {
		return meetingRepository.findByMeetLinkContainingIgnoreCase(code).stream()
				.filter(meeting -> meeting.getStatus() != MeetingStatus.CANCELLED)
				.filter(meeting -> code.equalsIgnoreCase(MeetingRooms.nativeMeetingId(meeting)))
				.toList();
	}

	private List<Meeting> currentSession(List<Meeting> meetings) {
		Instant joinHorizon = clock.instant().plus(TranscriptBotConstants.BOT_JOIN_LEAD_MINUTES, ChronoUnit.MINUTES);
		List<Instant> starts = meetings.stream().map(Meeting::getScheduledAt).filter(Objects::nonNull).toList();
		Instant session = starts.stream()
				.filter(start -> !start.isAfter(joinHorizon))
				.max(Comparator.naturalOrder())
				.orElseGet(() -> starts.stream().min(Comparator.naturalOrder()).orElse(null));
		if (session == null) {
			return List.of();
		}
		return meetings.stream().filter(meeting -> session.equals(meeting.getScheduledAt())).toList();
	}

	private static boolean isMentor(Meeting meeting, UUID callerId) {
		return meeting.getMentor() != null && callerId.equals(meeting.getMentor().getId());
	}

	private static boolean isTeamMember(Meeting meeting, @Nullable UUID callerOrganizationId) {
		return callerOrganizationId != null
				&& meeting.getOrganization() != null
				&& callerOrganizationId.equals(meeting.getOrganization().getId());
	}
}
