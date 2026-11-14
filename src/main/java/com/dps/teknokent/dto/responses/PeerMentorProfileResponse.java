package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.Map;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;

/** The signed-in peer mentor's own published profile (the wizard's answers), plus what ODTÜ says about them. */
public record PeerMentorProfileResponse(
		List<PeerTopicCategory> categories,
		List<String> subTopics,
		List<String> strongCourses,
		List<String> projects,
		String erasmus,
		String helpWith,
		String linkedinUrl,
		String department,
		Integer classYear,
		Integer menteeCapacity,
		Map<PeerTopicCategory, PeerTopicLevel> levels,
		List<PeerExperienceCardResponse> experiences) {
}
