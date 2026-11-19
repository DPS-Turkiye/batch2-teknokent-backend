package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.MentorLabelColor;
import com.dps.teknokent.domain.MentorLabelLimits;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A new label, or a label's new name and color. */
public record MentorLabelRequest(
		UUID actorId,
		@NotBlank @Size(max = MentorLabelLimits.NAME_MAX_LENGTH) String name,
		@NotNull MentorLabelColor color) {
}
