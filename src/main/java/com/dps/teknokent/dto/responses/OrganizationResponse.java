package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.Organization;

public record OrganizationResponse(
		UUID id,
		String name,
		String description,
		String website,
		String problem,
		String solution,
		String currentSolution,
		String targetUser,
		String sector,
		String maturity,
		String programLevel,
		List<String> supportAreas,
		Instant createdAt,
		Instant updatedAt) {

	public static OrganizationResponse fromEntity(Organization organization) {
		return new OrganizationResponse(organization.getId(), organization.getName(), organization.getDescription(),
				organization.getWebsite(), organization.getProblem(), organization.getSolution(),
				organization.getCurrentSolution(), organization.getTargetUser(),
				organization.getSector() == null ? null : organization.getSector().value(),
				organization.getMaturity() == null ? null : organization.getMaturity().value(),
				organization.getProgramLevel() == null ? null : organization.getProgramLevel().value(),
				List.copyOf(organization.getSupportAreas()),
				organization.getCreatedAt(), organization.getUpdatedAt());
	}
}
