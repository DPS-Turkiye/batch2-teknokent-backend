package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateStartupRequest(
		UUID actorId,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 5000) String description,
		@Size(max = 255) String website,
		@Size(max = 255) String mvpUrl,
		@Min(1900) @Max(2100) Integer foundedYear,
		@Size(max = 5000) String problem,
		@Size(max = 5000) String solution,
		@Size(max = 5000) String currentSolution,
		@Size(max = 5000) String targetUser,
		@Size(max = 255) String sector,
		@Size(max = 255) String maturity,
		@Size(max = 255) String programLevel,
		List<@Size(max = 255) String> supportAreas,
		@Size(max = 20) List<@Valid OnboardingTeamMemberRequest> teamMembers) implements StartupProfileFields {
}
