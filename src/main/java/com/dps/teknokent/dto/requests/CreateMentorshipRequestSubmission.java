package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestUrgency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.Valid;

public record CreateMentorshipRequestSubmission(
		@NotNull UUID requesterId,
		@NotNull UUID organizationId,
		@NotNull UUID mentorId,
		@NotBlank @Size(max = 255) String requestedForName,
		@NotBlank @Size(max = 255) String supportArea,
		@NotBlank @Size(max = 5000) String summary,
		@NotNull MentorshipRequestUrgency urgency,
		@NotNull MentorshipRequestFormat mentorshipFormat,
		@NotNull MeetingMode meetingMode,
		@Size(max = 5000) String requesterMessage,
		@Valid RecommendationSnapshotSubmission recommendationSnapshot) {
}
