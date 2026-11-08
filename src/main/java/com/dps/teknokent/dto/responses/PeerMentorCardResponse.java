package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

/** A peer mentor as a student of the same program sees them (frontend PeerMentorCard). */
public record PeerMentorCardResponse(
		UUID mentorId,
		String fullName,
		String department,
		Integer classYear,
		List<PeerTopicCategory> categories,
		String helpWith,
		List<String> recommendationReasons,
		boolean capacityFull,
		/** Active badges, as anyone may see them; a card shows the achievements. */
		List<PublicBadgeResponse> badges) {
}
