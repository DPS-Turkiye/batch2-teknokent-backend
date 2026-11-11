package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The student's interests as the profile completion and Profilim save them: categories active in their program,
 * sub-topic ids under them, a goal, and whether the completion was finished or skipped. The whole set is replaced.
 */
public record SaveStudentProfileCompletionRequest(
		UUID actorId,
		@NotNull @Size(max = 8) List<@NotNull PeerTopicCategory> topics,
		@NotNull @Size(max = 40) List<@NotNull @Pattern(regexp = "^[a-z0-9-]{1,64}$") String> subtopics,
		@NotNull @Size(max = 1000) String goal,
		boolean completed,
		boolean skipped) {
}
