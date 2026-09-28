package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.TranscriptBotConstants;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

/**
 * How many meeting rooms the transcript bot can record at the same moment. One bot joins per Meet link,
 * so guest copies of a meeting share a room and count once.
 */
@Service
public class TranscriptBotCapacityService {

	/** Longest meeting we look back for when checking what overlaps a slot. */
	private static final Duration LOOKBACK = Duration.ofHours(24);

	private final MeetingRepository meetingRepository;
	// ponytail: fixed below what the Vexa host can run; more signed-in bot accounts would raise it.
	private final int capacity;

	public TranscriptBotCapacityService(
			MeetingRepository meetingRepository,
			@Value("${meeting.transcript-bot.max-concurrent:2}") int capacity) {
		this.meetingRepository = meetingRepository;
		this.capacity = capacity;
	}

	public int capacity() {
		return capacity;
	}

	/** Scheduled meetings that asked for the bot and can overlap [from, to). */
	public List<Meeting> recordedAround(Instant from, Instant to) {
		return meetingRepository.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(
				MeetingStatus.SCHEDULED, from.minus(LOOKBACK), to);
	}

	/** Turns the bot off for a meeting whose slot is already full, and says whether it did. */
	public boolean releaseIfFull(Meeting meeting) {
		if (!meeting.isTranscribeRequested()
				|| meeting.getStatus() != MeetingStatus.SCHEDULED
				|| meeting.getScheduledAt() == null
				|| meeting.getBotDispatchedAt() != null) {
			return false;
		}
		Instant start = meeting.getScheduledAt();
		Instant end = endOf(meeting);
		if (peak(recordedAround(start, end), start, end, meeting) < capacity) {
			return false;
		}
		meeting.setTranscribeRequested(false);
		return true;
	}

	/** The most rooms recorded at one moment of [start, end), leaving out {@code self}'s own room. */
	static int peak(List<Meeting> recorded, Instant start, Instant end, Meeting self) {
		String ownRoom = self == null ? null : room(self);
		Map<String, Meeting> byRoom = new LinkedHashMap<>();
		for (Meeting meeting : recorded) {
			boolean isSelf = self != null && self.getId() != null && Objects.equals(meeting.getId(), self.getId());
			if (isSelf || meeting.getScheduledAt() == null) {
				continue;
			}
			String room = room(meeting);
			if (room.equals(ownRoom)) {
				continue;
			}
			if (meeting.getScheduledAt().isBefore(end) && endOf(meeting).isAfter(start)) {
				byRoom.putIfAbsent(room, meeting);
			}
		}
		// A meeting ending exactly when another starts does not overlap it, so ends sort before starts.
		List<Map.Entry<Instant, Integer>> changes = new ArrayList<>();
		for (Meeting meeting : byRoom.values()) {
			changes.add(Map.entry(later(start, meeting.getScheduledAt()), 1));
			changes.add(Map.entry(earlier(end, endOf(meeting)), -1));
		}
		changes.sort(Comparator.<Map.Entry<Instant, Integer>, Instant>comparing(Map.Entry::getKey)
				.thenComparing(Map.Entry::getValue));
		int current = 0;
		int peak = 0;
		for (Map.Entry<Instant, Integer> change : changes) {
			current += change.getValue();
			peak = Math.max(peak, current);
		}
		return peak;
	}

	static Instant endOf(Meeting meeting) {
		Integer minutes = meeting.getDurationMinutes();
		return meeting.getScheduledAt().plus(
				minutes == null ? TranscriptBotConstants.DEFAULT_DURATION_MINUTES : minutes, ChronoUnit.MINUTES);
	}

	private static String room(Meeting meeting) {
		String link = meeting.getMeetLink() == null ? "" : meeting.getMeetLink().trim();
		return link.isEmpty() ? "meeting:" + meeting.getId() : link;
	}

	private static Instant later(Instant first, Instant second) {
		return first.isAfter(second) ? first : second;
	}

	private static Instant earlier(Instant first, Instant second) {
		return first.isBefore(second) ? first : second;
	}
}
