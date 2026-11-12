package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The peer-mentor wizard's answers: categories, sub-topics, relevant experience (strong courses, projects, exchange),
 * what the student can help with, an optional LinkedIn link, a level per category and experience cards. Availability and capacity are saved through the
 * mentor's own availability settings.
 */
public record SavePeerMentorProfileRequest(
		UUID actorId,
		@NotEmpty @Size(max = 8) List<PeerTopicCategory> categories,
		@Size(max = 20) List<@NotBlank @Size(max = 100) String> subTopics,
		@Size(max = 10) List<@NotBlank @Size(max = 255) String> strongCourses,
		@Size(max = 10) List<@NotBlank @Size(max = 500) String> projects,
		@Size(max = 500) String erasmus,
		@NotBlank @Size(min = 20, max = 2000) String helpWith,
		@Size(max = 255) @Pattern(regexp = "^$|^https?://.+", message = "must be a http(s) link") String linkedinUrl,
		/** A level for some of the chosen categories; left out, the saved levels stay. */
		@Size(max = 8) Map<PeerTopicCategory, PeerTopicLevel> levels,
		/** The experience cards, in order; left out, the saved cards stay, an empty list removes them. */
		@Size(max = 10) List<@NotNull @Valid PeerExperienceCardRequest> experiences) {
}
