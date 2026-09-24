package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipStatus;

/** One row of the coordinator's Mentorluklar table. */
public record CoordinatorMentorshipResponse(
		UUID id,
		UUID programId,
		String programName,
		UUID mentorId,
		String mentorName,
		String mentorTitle,
		UUID organizationId,
		String organizationName,
		MentorshipStatus status,
		CoordinatorMentorshipHealth health,
		String topic,
		LocalDate startDate,
		Instant lastMeetingAt,
		Long daysSinceLastMeeting,
		Instant nextMeetingAt,
		int openActions,
		int overdueActions,
		int missingNotes) {

	/**
	 * The drawer. Goal titles stay between the mentor and the team, so the coordinator only gets the counts.
	 */
	public record Detail(
			CoordinatorMentorshipResponse mentorship,
			int meetingFrequencyDays,
			int staleAfterDays,
			int pastMeetings,
			int notedMeetings,
			Instant lastNoteAt,
			int goalsDone,
			int goalsTotal,
			long mentorLoad,
			Integer mentorCapacity,
			String founderName,
			int memberCount,
			List<MeetingRow> meetings) {
	}

	public record MeetingRow(
			UUID id,
			String title,
			Instant scheduledAt,
			Integer durationMinutes,
			MeetingStatus status,
			boolean hasNotes) {
	}
}
