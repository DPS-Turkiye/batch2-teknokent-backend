package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The "Durum" card on the startup profile. Kept apart from the profile PUT,
 * which replaces every field it carries, so that saving the profile form can
 * never wipe the status.
 */
public record UpdateOrganizationStatusRequest(
		@NotNull UUID actorId,
		@Size(max = 32) String statusVersion,
		@Size(max = 5000) String statusSummary,
		@Min(0) @Max(100) Integer completionPercent) {
}
