package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.PeerActionOwner;
import com.dps.teknokent.domain.PeerMeetingFormat;
import com.dps.teknokent.domain.PeerMeetingTopic;
import com.dps.teknokent.domain.PeerMeetingType;
import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PeerReportStatus;

/**
 * A meeting report, or the calendar's prefill of one that was never saved ({@code id} and {@code status} null).
 * {@code context} is the side card: which meeting this is with the mentee, the one before, open actions.
 */
public record PeerMeetingReportResponse(
		UUID id,
		UUID meetingId,
		PeerReportStatus status,
		boolean editable,
		Mentee mentee,
		Instant scheduledAt,
		int plannedDurationMinutes,
		Context context,
		PeerMeetingType meetingType,
		PeerMeetingFormat meetingFormat,
		Integer actualDurationMinutes,
		Set<PeerMeetingTopic> topics,
		String menteeNeed,
		String discussion,
		String approach,
		Set<PeerMentorSkill> skills,
		String skillExample,
		String outcome,
		List<Action> actions,
		boolean followUpNeeded,
		Instant followUpAt,
		Integer productivity,
		String nextTime,
		Boolean coordinatorSupportNeeded,
		Instant submittedAt,
		String revisionNote,
		Instant updatedAt) {

	public record Mentee(UUID id, String fullName, String department) {
	}

	public record Context(int meetingNumber, PreviousMeeting previousMeeting, int openActions) {
	}

	public record PreviousMeeting(UUID meetingId, Instant scheduledAt, Set<PeerMeetingTopic> topics) {
	}

	public record Action(UUID id, String text, PeerActionOwner owner, LocalDate dueDate, boolean done) {
	}
}
