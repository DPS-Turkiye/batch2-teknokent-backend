package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

public record ProfileAnalysisResponse(
		UUID id,
		UUID organizationId,
		String sourceFilename,
		String profileType,
		String profile,
		String status,
		Instant consentAcceptedAt,
		String redactionSummary,
		String ocrSummary,
		Instant createdAt) {
}
