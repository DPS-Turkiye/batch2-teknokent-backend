package com.dps.teknokent.dto.requests;

import java.util.Set;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** The sender's confirmation of one review: which list they saw, whom they took off and how many remain. */
public record SendEmailCampaignRequest(
		UUID actorId,
		@NotBlank @Size(max = 64) String reviewDigest,
		@NotNull @Size(max = 5000) Set<@NotNull UUID> untickedIds,
		@NotNull @Positive Integer expectedCount) {
}
