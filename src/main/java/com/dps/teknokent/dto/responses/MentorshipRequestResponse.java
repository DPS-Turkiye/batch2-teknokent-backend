package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.MentorshipRequestUrgency;

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
		RecommendationSnapshotResponse recommendationSnapshot) {
}
