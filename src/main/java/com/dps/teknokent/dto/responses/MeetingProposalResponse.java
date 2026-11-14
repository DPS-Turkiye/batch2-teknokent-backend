package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.MeetingProposalStatus;

/** {@code canRespond} is from the viewer's side: the mentor, or the team lead, who has not answered yet. */
public record MeetingProposalResponse(
		UUID id,
		@Nullable UUID mentorshipId,
		UUID programId,
		@Nullable String programName,
		UUID mentorId,
		String mentorName,
		UUID organizationId,
		String organizationName,
		@Nullable String proposedByName,
		Instant scheduledAt,
		int durationMinutes,
		String agenda,
		UUID replacesMeetingId,
		MeetingProposalStatus status,
		Instant mentorAcceptedAt,
		Instant teamAcceptedAt,
		@Nullable String teamResponderName,
		@Nullable String declinedByName,
		String declineReason,
		UUID meetingId,
		Instant remindedAt,
		Instant createdAt,
		boolean canRespond) {
}
