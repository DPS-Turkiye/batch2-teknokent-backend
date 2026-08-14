package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record OrganizationResponse(
		UUID id,
		String name,
		String description,
		Instant createdAt,
		Instant updatedAt) {
}
