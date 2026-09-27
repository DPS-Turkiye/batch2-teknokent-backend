package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingProposalStatus;

/** {@code canRespond} is from the viewer's side: the mentor, or the team lead, who has not answered yet. */
public record MeetingProposalResponse(
		UUID id,
		UUID mentorshipId,
		UUID programId,
		String programName,
		UUID mentorId,
		String mentorName,
		UUID organizationId,
		String organizationName,
		String proposedByName,
		Instant scheduledAt,
		int durationMinutes,
		String agenda,
		UUID replacesMeetingId,
		MeetingProposalStatus status,
		Instant mentorAcceptedAt,
		Instant teamAcceptedAt,
		String teamResponderName,
		String declinedByName,
		String declineReason,
		UUID meetingId,
		Instant remindedAt,
		Instant createdAt,
		boolean canRespond) {
}
