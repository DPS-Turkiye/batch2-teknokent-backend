package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.MeetingFeedback;

public record MeetingFeedbackResponse(
		UUID id,
		UUID meetingId,
		UUID authorId,
		List<Integer> ratings,
		List<String> benefits,
		Instant createdAt) {

	public static MeetingFeedbackResponse fromEntity(MeetingFeedback feedback) {
		return new MeetingFeedbackResponse(
				feedback.getId(),
				feedback.getMeeting().getId(),
				feedback.getAuthor().getId(),
				List.copyOf(feedback.getRatings()),
				List.copyOf(feedback.getBenefits()),
				feedback.getCreatedAt());
	}
}
