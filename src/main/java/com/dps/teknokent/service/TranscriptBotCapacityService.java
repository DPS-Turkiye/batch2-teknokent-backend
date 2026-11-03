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
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.util.Texts;

/**
 * How many meeting rooms the transcript bot can record at the same moment. One bot joins per Meet link,
 * so guest copies of a meeting share a room and count once.
 */
@Service
public class TranscriptBotCapacityService {

	/** Longest meeting we look back for when checking what overlaps a slot. */
	private static final Duration LOOKBACK = Duration.ofHours(24);
	/** A bot joins this long before its meeting and is counted as still in the room this long after it. */
	static final Duration BOT_PADDING = Duration.ofMinutes(5);
	/** How far ahead a full slot looks for the next free start to suggest. */
	private static final Duration SEARCH_AHEAD = Duration.ofHours(24);
	/** How long a bot asked for from the assistant is assumed to stay. */
	private static final Duration AD_HOC_WINDOW = Duration.ofHours(1);

	private final MeetingRepository meetingRepository;
	// Vexa refuses a 4th bot for one user, so 3 is the ceiling until more bot accounts are signed in.
	private final int capacity;

	public TranscriptBotCapacityService(
			MeetingRepository meetingRepository,
			@Value("${meeting.transcript-bot.max-concurrent:3}") int capacity) {
		this.meetingRepository = meetingRepository;
		this.capacity = capacity;
	}

	public int capacity() {
		return capacity;
	}

	/** Scheduled meetings that asked for the bot and whose bot can be in a room during [from, to). */
	public List<Meeting> recordedAround(Instant from, Instant to) {
		return meetingRepository.findByTranscribeRequestedTrueAndStatusAndScheduledAtBetween(
				MeetingStatus.SCHEDULED, from.minus(LOOKBACK), to.plus(BOT_PADDING));
	}

	/**
	 * Booked bots not sent yet that will be in a room at some moment of the next hour. Bots already sent are in
	 * the provider's running count, so they are left out here.
	 */
	public int reservedWithinHour(Instant now) {
		Instant until = now.plus(AD_HOC_WINDOW);
		List<Meeting> waiting = recordedAround(now, until).stream()
				.filter(meeting -> meeting.getBotDispatchedAt() == null)
				.toList();
		return peak(waiting, now, until, null);
	}

	/** Refuses to save a recorded meeting whose time already has every bot taken. */
	public void requireSlot(Meeting meeting) {
		requireSlot(meeting, meeting.getScheduledAt(), meeting.getDurationMinutes());
	}

	/** The same, for a recorded meeting about to move to {@code start}. */
	public void requireSlot(Meeting meeting, Instant start, Integer durationMinutes) {
		if (!meeting.isTranscribeRequested()
				|| meeting.getStatus() != MeetingStatus.SCHEDULED
				|| start == null
				|| meeting.getBotDispatchedAt() != null) {
			return;
		}
		Duration length = Duration.ofMinutes(
				durationMinutes == null ? TranscriptBotConstants.DEFAULT_DURATION_MINUTES : durationMinutes);
		List<Meeting> recorded = recordedAround(start, start.plus(length).plus(SEARCH_AHEAD));
		// ponytail: check and save are not locked, so two bookings at the same moment can both pass; the dispatcher
		// retries on Vexa's 429 until a bot frees up. An advisory lock if that ever shows up in practice.
		if (peak(recorded, start, start.plus(length), meeting) < capacity) {
			return;
		}
		Instant next = nextFreeStart(recorded, meeting, start, length);
		throw new ConflictException(next == null
				? Texts.forRequest("error.recordingBotsFull", capacity)
				: Texts.forRequest("error.recordingBotsFullNext", capacity, next));
	}

	/**
	 * The earliest later start where the meeting fits. A slot only frees up when another bot leaves the room, so
	 * the candidates are the moments right after each other recorded meeting's bot has gone.
	 */
	private Instant nextFreeStart(List<Meeting> recorded, Meeting self, Instant start, Duration length) {
		return recorded.stream()
				.map(other -> endOf(other).plus(BOT_PADDING.multipliedBy(2)))
				.filter(candidate -> candidate.isAfter(start) && candidate.isBefore(start.plus(SEARCH_AHEAD)))
				.sorted()
				.filter(candidate -> peak(recorded, candidate, candidate.plus(length), self) < capacity)
				.findFirst()
				.orElse(null);
	}

	/**
	 * The most bots in rooms at one moment while {@code self}'s bot would be in its room, leaving out
	 * {@code self}'s own room. Every bot is counted from {@link #BOT_PADDING} before its meeting to as long after.
	 */
	static int peak(List<Meeting> recorded, Instant meetingStart, Instant meetingEnd, Meeting self) {
		Instant start = meetingStart.minus(BOT_PADDING);
		Instant end = meetingEnd.plus(BOT_PADDING);
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
			if (botJoins(meeting).isBefore(end) && botLeaves(meeting).isAfter(start)) {
				byRoom.putIfAbsent(room, meeting);
			}
		}
		// A bot leaving exactly when another joins does not overlap it, so leaves sort before joins.
		List<Map.Entry<Instant, Integer>> changes = new ArrayList<>();
		for (Meeting meeting : byRoom.values()) {
			changes.add(Map.entry(later(start, botJoins(meeting)), 1));
			changes.add(Map.entry(earlier(end, botLeaves(meeting)), -1));
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

	private static Instant botJoins(Meeting meeting) {
		return meeting.getScheduledAt().minus(BOT_PADDING);
	}

	private static Instant botLeaves(Meeting meeting) {
		return endOf(meeting).plus(BOT_PADDING);
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
