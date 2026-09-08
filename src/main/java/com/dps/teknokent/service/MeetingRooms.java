package com.dps.teknokent.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.repository.MeetingRepository;

final class MeetingRooms {

	private MeetingRooms() {
	}

	static List<Meeting> of(Meeting meeting, MeetingRepository meetingRepository) {
		Map<UUID, Meeting> room = new LinkedHashMap<>();
		room.put(meeting.getId(), meeting);
		String nativeId = nativeMeetingId(meeting);
		if (nativeId != null) {
			for (Meeting mate : meetingRepository.findByMeetLinkContainingIgnoreCase(nativeId)) {
				if (nativeId.equalsIgnoreCase(nativeMeetingId(mate))) {
					room.putIfAbsent(mate.getId(), mate);
				}
			}
		}
		Instant scheduledAt = meeting.getScheduledAt();
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

	static String nativeMeetingId(Meeting meeting) {
		if (meeting == null) {
			return null;
		}
		return MeetingUrlParser.tryParse(meeting.getMeetLink())
				.map(MeetingUrlParser.Parsed::nativeMeetingId)
				.orElse(null);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
