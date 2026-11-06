package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;
import com.dps.teknokent.entity.MentorshipRequest;

public record MentorshipRequestResponse(
		UUID id,
		UUID requesterId,
		String requesterName,
		UUID organizationId,
		String organizationName,
		UUID mentorId,
		String mentorName,
		String requestedForName,
		String supportArea,
		String summary,
		MentorshipRequestUrgency urgency,
		MentorshipRequestFormat mentorshipFormat,
		MeetingMode meetingMode,
		MentorshipRequestStatus status,
		String requesterMessage,
		String mentorMessage,
		String rejectionReason,
		Instant createdAt,
		Instant updatedAt,
		List<MentorshipRequestMessageResponse> messages,
		RecommendationSnapshotResponse recommendationSnapshot,
		Integer plannedSessionCount,
		Instant mentorReadAt,
		Instant menteeReadAt,
		String expectedOutcome,
		/** The mentee's program when they asked; null for a startup outside any program. */
		UUID programId,
		String programName) {

	public static MentorshipRequestResponse fromEntity(
			MentorshipRequest request,
			List<MentorshipRequestMessageResponse> messages,
			RecommendationSnapshotResponse recommendationSnapshot) {
		return new MentorshipRequestResponse(
				request.getId(),
				request.getRequester().getId(),
				request.getRequester().getFullName(),
				request.getOrganization().getId(),
				request.getOrganization().getName(),
				request.getMentor().getId(),
				request.getMentor().getFullName(),
				request.getRequestedForName(),
				request.getSupportArea(),
				request.getSummary(),
				request.getUrgency(),
				request.getMentorshipFormat(),
				request.getMeetingMode(),
				request.getStatus(),
				request.getRequesterMessage(),
				request.getMentorMessage(),
				request.getRejectionReason(),
				request.getCreatedAt(),
				request.getUpdatedAt(),
				List.copyOf(messages),
				recommendationSnapshot,
				request.getPlannedSessionCount(),
				request.getMentorReadAt(),
				request.getMenteeReadAt(),
				request.getExpectedOutcome(),
				request.getProgram() == null ? null : request.getProgram().getId(),
				request.getProgram() == null ? null : request.getProgram().getName());
	}
}
