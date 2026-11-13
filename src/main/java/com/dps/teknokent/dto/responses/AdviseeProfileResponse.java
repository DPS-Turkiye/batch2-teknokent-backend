package com.dps.teknokent.dto.responses;

import java.util.List;

import com.dps.teknokent.domain.PeerTopicCategory;

/**
 * An advisee's interests and goal as their academic advisor sees them: what the student saved on Profilim, with the
 * same topics their own Profilim shows. Empty until the student saves once.
 */
public record AdviseeProfileResponse(Interests interests, String goal) {

	public static final AdviseeProfileResponse EMPTY = new AdviseeProfileResponse(
			new Interests(List.of(), List.of()), "");

	public record Interests(List<PeerTopicCategory> topics, List<String> subtopics) {
	}
}
