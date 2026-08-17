package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;
import com.dps.teknokent.entity.Bottleneck;

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

	public static BottleneckResponse fromEntity(Bottleneck bottleneck) {
		return new BottleneckResponse(
				bottleneck.getId(), bottleneck.getOrganization().getId(),
				bottleneck.getOwner() == null ? null : bottleneck.getOwner().getId(),
				bottleneck.getOwner() == null ? null : bottleneck.getOwner().getFullName(),
				bottleneck.getTitle(), bottleneck.getDescription(), bottleneck.getSeverity(), bottleneck.getStatus(),
				bottleneck.getTargetResolutionDate(), bottleneck.getResolutionNotes(),
				bottleneck.getCreatedAt(), bottleneck.getUpdatedAt());
	}
}
