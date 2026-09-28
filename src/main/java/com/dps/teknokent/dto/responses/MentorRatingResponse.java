package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

/**
 * "Ortalama değerlendirme": the average of every 1–5 answer mentees gave in meeting feedback, overall and per
 * mentorship (request). {@code average} is null until the first feedback.
 */
public record MentorRatingResponse(Double average, long feedbackCount, List<Mentorship> mentorships) {

	public record Mentorship(UUID mentorshipRequestId, double average, long feedbackCount) {
	}
}
