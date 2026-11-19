package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MentorLabelLimits;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Every label the mentor carries in the program after the change; an empty list takes them all off. */
public record SetMentorLabelsRequest(
		UUID actorId,
		@NotNull @Size(max = MentorLabelLimits.PER_MENTOR) List<@NotNull UUID> labelIds) {
}
