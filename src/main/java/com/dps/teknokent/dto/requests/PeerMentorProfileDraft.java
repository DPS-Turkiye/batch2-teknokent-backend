package com.dps.teknokent.dto.requests;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import com.dps.teknokent.domain.PeerExperienceKind;
import com.dps.teknokent.domain.PeerTopicCategory;
import com.dps.teknokent.domain.PeerTopicLevel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The peer-mentor wizard part-way through: the same answers as {@link SavePeerMentorProfileRequest}, with its upper
 * limits but none of its minimums, since a draft may be empty or half typed. {@code step} is the wizard step to
 * reopen on.
 */
public record PeerMentorProfileDraft(
		@Size(max = 32) String step,
		@Size(max = 8) List<@NotNull PeerTopicCategory> categories,
		@Size(max = 20) List<@NotNull @Size(max = 100) String> subTopics,
		@Size(max = 10) List<@NotNull @Size(max = 255) String> strongCourses,
		@Size(max = 10) List<@NotNull @Size(max = 500) String> projects,
		@Size(max = 500) String erasmus,
		@Size(max = 2000) String helpWith,
		@Size(max = 255) String linkedinUrl,
		@Size(max = 8) Map<PeerTopicCategory, @NotNull PeerTopicLevel> levels,
		@Size(max = 10) List<@NotNull @Valid Card> experiences) {

	/** An experience card being filled in: any field may still be empty. */
	public record Card(
			PeerExperienceKind kind,
			@Size(max = 200) String title,
			@Size(max = 200) String organization,
			YearMonth startedOn,
			YearMonth endedOn,
			@Size(max = 300) String summary) {
	}
}
