package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;

public record BottleneckResponse(
		UUID id,
		UUID organizationId,
		UUID ownerId,
		String ownerName,
		String title,
		String description,
		BottleneckSeverity severity,
		BottleneckStatus status,
		LocalDate targetResolutionDate,
		String resolutionNotes,
		Instant createdAt,
		Instant updatedAt) {
}
