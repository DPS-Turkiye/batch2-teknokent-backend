package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The startup profile as the founder can edit it after signup. Mirrors the
 * wizard's second step field for field, so the edit screen and the wizard stay
 * the same shape; the invite code, founder and active program are deliberately
 * absent, since those are not the founder's to change here.
 *
 * <p>
 * {@code actorId} identifies the caller, matching the invite-code endpoints on
 * the same controller rather than inventing a second convention.
 */
public record UpdateOrganizationRequest(
		@NotNull UUID actorId,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 5000) String description,
		@Size(max = 255) String website,
		@Size(max = 5000) String problem,
		@Size(max = 5000) String solution,
		@Size(max = 5000) String currentSolution,
		@Size(max = 5000) String targetUser,
		@Size(max = 255) String sector,
		@Size(max = 255) String maturity,
		@Size(max = 255) String programLevel,
		List<@Size(max = 255) String> supportAreas) {
}
