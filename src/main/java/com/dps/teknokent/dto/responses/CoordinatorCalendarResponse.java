package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.CoordinatorAgendaKind;

/** The coordinator's Takvim for a date range: mentorship meetings, program events and deadlines. */
public record CoordinatorCalendarResponse(
		LocalDate from,
		LocalDate to,
		List<Item> items,
		List<Conflict> conflicts,
		Summary summary) {

	/** {@code id} is the meeting's id for MEETING and the program event's id otherwise. */
	public record Item(
			UUID id,
			CoordinatorAgendaKind kind,
			String title,
			Instant startsAt,
			Instant endsAt,
			UUID programId,
			String programName,
			UUID mentorshipId,
			UUID mentorId,
			String mentorName,
			UUID organizationId,
			String organizationName,
			String topic,
			Integer sequence,
			String meetLink,
			String description,
			boolean conflict) {
	}

	/** Meetings of one mentor that overlap in time. */
	public record Conflict(UUID mentorId, String mentorName, List<UUID> meetingIds) {
	}

	/** {@code unscheduledRisky}: risky mentorships with no meeting booked at all, whatever the range. */
	public record Summary(int meetings, int events, int deadlines, int unscheduledRisky) {
	}
}
