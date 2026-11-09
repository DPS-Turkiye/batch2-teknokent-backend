package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A student asks a peer mentor of their program for help on one of the board's categories. */
public record CreatePeerMentoringRequest(
		UUID actorId,
		@NotNull UUID mentorId,
		@NotNull PeerTopicCategory category,
		@NotBlank @Size(min = 20, max = 1000) String message) {
}
