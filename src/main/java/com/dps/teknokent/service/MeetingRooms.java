package com.dps.teknokent.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

final class MeetingRooms {

	private MeetingRooms() {
	}

	/**
	 * The copies of one session: the meeting itself and every meeting in the same room at the same start, however
	 * its link is spelled. Mentors reuse one personal Meet room for every team, so the room alone never joins two
	 * meetings; without a start time a meeting is a room of its own.
	 */
	static List<Meeting> of(Meeting meeting, MeetingRepository meetingRepository) {
		Map<UUID, Meeting> room = new LinkedHashMap<>();
		room.put(meeting.getId(), meeting);
		String nativeId = nativeMeetingId(meeting);
		Instant scheduledAt = meeting.getScheduledAt();
		if (nativeId != null && scheduledAt != null) {
			for (Meeting mate : meetingRepository.findByMeetLinkContainingIgnoreCase(nativeId)) {
				if (nativeId.equalsIgnoreCase(nativeMeetingId(mate)) && scheduledAt.equals(mate.getScheduledAt())) {
					room.putIfAbsent(mate.getId(), mate);
				}
			}
		}
		String meetLink = meeting.getMeetLink();
		if (hasText(meetLink) && scheduledAt != null) {
			for (Meeting mate : meetingRepository.findByMeetLinkAndScheduledAt(meetLink, scheduledAt)) {
				room.putIfAbsent(mate.getId(), mate);
			}
		}
		if (nativeId == null && meeting.getMentor() != null && scheduledAt != null) {
			for (Meeting mate : meetingRepository.findByMentorIdAndScheduledAt(
					meeting.getMentor().getId(), scheduledAt)) {
				if (nativeMeetingId(mate) == null) {
					room.putIfAbsent(mate.getId(), mate);
				}
			}
		}
		return List.copyOf(room.values());
	}

	static @Nullable String nativeMeetingId(@Nullable Meeting meeting) {
		if (meeting == null) {
			return null;
		}
		return MeetingUrlParser.tryParse(meeting.getMeetLink())
				.map(MeetingUrlParser.Parsed::nativeMeetingId)
				.orElse(null);
	}

	private static boolean hasText(@Nullable String value) {
		return value != null && !value.isBlank();
	}
}
