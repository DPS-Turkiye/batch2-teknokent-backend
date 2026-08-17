package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.Organization;

public record OrganizationResponse(
		UUID id,
		String name,
		String description,
		Instant createdAt,
		Instant updatedAt) {

	public static OrganizationResponse fromEntity(Organization organization) {
		return new OrganizationResponse(organization.getId(), organization.getName(), organization.getDescription(),
				organization.getCreatedAt(), organization.getUpdatedAt());
	}
}
