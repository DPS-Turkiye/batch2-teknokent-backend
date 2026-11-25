package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Gives every upcoming online meeting without a link a Google Meet link. It looks for such meetings instead of being
 * called where they are made, because they are made in many places, and a failed try is simply found again later.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.google-meet", name = "enabled", havingValue = "true")
public class MeetLinkService {

	/** Time for the mentor's own link, which the app saves right after the meeting. */
	static final Duration GRACE = Duration.ofMinutes(1);
	/** After a failure Google is left alone this long. */
	static final Duration RETRY_AFTER = Duration.ofMinutes(10);
	// Few at a time: the scheduled jobs share one thread, and Google may answer slowly.
	private static final int BATCH = 10;

	private final MeetingRepository meetingRepository;
	private final GoogleMeetClient googleMeetClient;
	private final Clock clock;
	private Instant notBefore = Instant.MIN;

	@Autowired
	public MeetLinkService(MeetingRepository meetingRepository, GoogleMeetClient googleMeetClient) {
		this(meetingRepository, googleMeetClient, Clock.systemUTC());
	}

	MeetLinkService(MeetingRepository meetingRepository, GoogleMeetClient googleMeetClient, Clock clock) {
		this.meetingRepository = meetingRepository;
		this.googleMeetClient = googleMeetClient;
		this.clock = clock;
	}

	/** A mentor's group session has one row per team at the same start: one room, so one link. */
	private record Room(UUID mentorId, Instant startsAt) {
	}

	/** Called by one scheduler only; no transaction is open while Google is asked. */
	public void createMissingLinks() {
		Instant now = clock.instant();
		if (now.isBefore(notBefore)) {
			return;
		}
		Map<Room, List<UUID>> rooms = new LinkedHashMap<>();
		for (MeetingRepository.MissingLink meeting : meetingRepository.findUpcomingWithoutLink(
				now, now.minus(GRACE), Limit.of(BATCH))) {
			rooms.computeIfAbsent(new Room(meeting.getMentorId(), meeting.getScheduledAt()),
					room -> new ArrayList<>()).add(meeting.getId());
		}
		for (Map.Entry<Room, List<UUID>> entry : rooms.entrySet()) {
			try {
				give(entry.getKey(), entry.getValue(), now);
			} catch (RuntimeException failure) {
				// Google's message names the status and reason, never the key or the token.
				log.warn("Google Meet link could not be created; next try after {}", RETRY_AFTER, failure);
				notBefore = now.plus(RETRY_AFTER);
				return;
			}
		}
	}

	private void give(Room room, List<UUID> meetingIds, Instant now) {
		String link = meetingRepository.findByMentorIdAndScheduledAt(room.mentorId(), room.startsAt()).stream()
				.filter(meeting -> !meeting.didNotTakePlace())
				.map(Meeting::getMeetLink)
				.filter(existing -> existing != null && !existing.isBlank())
				.findFirst()
				.orElseGet(googleMeetClient::createMeeting);
		meetingRepository.setMissingLink(meetingIds, link, now);
	}
}
