package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.BottleneckSeverity;
import com.dps.teknokent.domain.BottleneckStatus;

import jakarta.validation.constraints.Size;

public record UpdateBottleneckRequest(
		UUID ownerId,
		@Size(max = 255) String title,
		@Size(max = 10000) String description,
		BottleneckSeverity severity,
		BottleneckStatus status,
		LocalDate targetResolutionDate,
		@Size(max = 10000) String resolutionNotes) {
}
