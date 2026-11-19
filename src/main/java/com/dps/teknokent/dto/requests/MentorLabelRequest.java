package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.MentorLabelColor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A new label, or a label's new name and color. The name's length is checked once it is tidied up (see
 * MentorLabelService#normalizedName), so spaces that collapse do not count against the limit.
 */
public record MentorLabelRequest(
		UUID actorId,
		@NotBlank String name,
		@NotNull MentorLabelColor color) {
}
