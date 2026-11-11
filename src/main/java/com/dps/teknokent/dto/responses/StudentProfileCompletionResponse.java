package com.dps.teknokent.dto.responses;

import java.util.List;

import com.dps.teknokent.domain.PeerTopicCategory;

/** Frontend StudentProfileCompletion. Empty, neither completed nor skipped, until the student saves once. */
public record StudentProfileCompletionResponse(
		List<PeerTopicCategory> topics,
		List<String> subtopics,
		String goal,
		boolean completed,
		boolean skipped) {
}
