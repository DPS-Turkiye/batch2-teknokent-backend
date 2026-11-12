package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipRequestStatus;
import com.dps.teknokent.domain.PeerTopicCategory;

/**
 * A peer-mentoring request (frontend PeerMentoringRequest). People only, never a team: the student files it under a
 * hidden personal team that is never shown.
 */
public record PeerMentoringRequestResponse(
		UUID id,
		Person mentor,
		Person mentee,
		PeerTopicCategory category,
		String message,
		MentorshipRequestStatus status,
		Instant createdAt,
		/** When the mentor accepted: the mentorship's start date; null until then. */
		Instant acceptedAt) {

	public record Person(UUID userId, String fullName, String department) {
	}
}
