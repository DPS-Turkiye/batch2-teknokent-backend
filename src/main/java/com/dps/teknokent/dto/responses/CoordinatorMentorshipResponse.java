package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.CoordinatorMentorshipHealth;
import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.MentorshipSource;
import com.dps.teknokent.domain.MentorshipStatus;

/**
 * One row of the coordinator's Mentorluklar table. A team working with a mentor through an accepted request has no
 * mentorship row: its {@code id} is null, {@code source} says so and {@code requestId} names the request. The drawer
 * and "İki tarafa hatırlat" take a mentorship id, so they are not offered for such a row.
 */
public record CoordinatorMentorshipResponse(
		@Nullable UUID id,
		UUID programId,
		String programName,
		UUID mentorId,
		String mentorName,
		String mentorTitle,
		UUID organizationId,
		String organizationName,
		MentorshipStatus status,
		CoordinatorMentorshipHealth health,
		@Nullable String topic,
		LocalDate startDate,
		@Nullable Instant lastMeetingAt,
		@Nullable Long daysSinceLastMeeting,
		@Nullable Instant nextMeetingAt,
		int openActions,
		int overdueActions,
		int missingNotes,
		MentorshipSource source,
		@Nullable UUID requestId) {

	/**
	 * The drawer. Goal titles stay between the mentor and the team, so the coordinator only gets the counts.
	 */
	public record Detail(
			CoordinatorMentorshipResponse mentorship,
			int meetingFrequencyDays,
			int staleAfterDays,
			int pastMeetings,
			int notedMeetings,
			@Nullable Instant lastNoteAt,
			int goalsDone,
			int goalsTotal,
			long mentorLoad,
			@Nullable Integer mentorCapacity,
			@Nullable String founderName,
			int memberCount,
			List<MeetingRow> meetings) {
	}

	public record MeetingRow(
			UUID id,
			String title,
			Instant scheduledAt,
			Integer durationMinutes,
			MeetingStatus status,
			boolean hasNotes,
			boolean noShow,
			@Nullable String noShowNote) {
	}
}
