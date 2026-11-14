package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MeetingMode;
import com.dps.teknokent.domain.MentorshipRequestFormat;
import com.dps.teknokent.domain.MentorshipRequestUrgency;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a mentee is looking for. The texts are sent to the paid embeddings API, so each is bounded; the limits match
 * {@link RecommendationSnapshotSubmission} and {@link CreateMentorshipRequestSubmission}, which carry the same values
 * on. {@code startupStage} stays free text: the frontend sends the startup's own stage label (or its maturity), not a
 * {@code StartupStage} step.
 */
public record RecommendMentorsRequest(
		@NotBlank @Size(max = 255) String supportArea,
		@NotBlank @Size(max = 5000) String summary,
		MentorshipRequestUrgency urgency,
		MentorshipRequestFormat mentorshipFormat,
		MeetingMode meetingMode,
		@Size(max = 500) String startupSector,
		@Size(max = 500) String startupStage,
		@Size(max = 5000) String startupSummary,
		@Size(max = 50) List<@Size(max = 255) String> startupTechnologies,
		UUID excludedMentorId) {
}
