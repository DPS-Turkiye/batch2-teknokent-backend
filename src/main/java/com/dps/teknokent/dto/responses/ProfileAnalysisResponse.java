package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.entity.ProfileAnalysis;

public record ProfileAnalysisResponse(
		UUID id,
		UUID organizationId,
		UUID appUserId,
		String sourceFilename,
		String profileType,
		String profile,
		String status,
		Instant consentAcceptedAt,
		String redactionSummary,
		String ocrSummary,
		Instant createdAt) {

	public static ProfileAnalysisResponse fromEntity(ProfileAnalysis analysis) {
		String displayedProfile = analysis.getConfirmedProfile() == null
				? analysis.getParsedProfile() : analysis.getConfirmedProfile();
		return new ProfileAnalysisResponse(
				analysis.getId(), analysis.getOrganizationId(), analysis.getAppUserId(), analysis.getSourceFilename(),
				analysis.getProfileType(), displayedProfile, analysis.getStatus(), analysis.getConsentAcceptedAt(),
				analysis.getRedactionSummary(), analysis.getOcrSummary(), analysis.getCreatedAt());
	}
}
