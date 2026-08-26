package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.dps.teknokent.dto.requests.CreateManualProfileRequest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "profile_analysis")
@Getter
@Setter
@NoArgsConstructor
public class ProfileAnalysis {

	public static ProfileAnalysis fromParsedProfile(
			UUID organizationId, UUID appUserId, String sourceFilename, String profileType, String parsedProfile,
			String redactionSummary, String ocrSummary, Instant now) {
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.id = UUID.randomUUID();
		analysis.organizationId = organizationId;
		analysis.appUserId = appUserId;
		analysis.sourceFilename = sourceFilename;
		analysis.profileType = profileType;
		analysis.parsedProfile = parsedProfile;
		analysis.consentAcceptedAt = now;
		analysis.redactionSummary = redactionSummary;
		analysis.ocrSummary = ocrSummary;
		analysis.createdAt = now;
		return analysis;
	}

	public static ProfileAnalysis fromManualProfile(
			UUID organizationId, UUID appUserId, CreateManualProfileRequest request, String serializedProfile,
			Instant now) {
		ProfileAnalysis analysis = new ProfileAnalysis();
		analysis.id = UUID.randomUUID();
		analysis.organizationId = organizationId;
		analysis.appUserId = appUserId;
		analysis.sourceFilename = "manual-entry";
		analysis.profileType = request.profileType();
		analysis.parsedProfile = serializedProfile;
		analysis.confirmedProfile = serializedProfile;
		analysis.status = "CONFIRMED";
		analysis.confirmedAt = now;
		analysis.createdAt = now;
		return analysis;
	}

	public void confirm(String serializedProfile, Instant now) {
		confirmedProfile = serializedProfile;
		status = "CONFIRMED";
		confirmedAt = now;
	}

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

	@Column(name = "app_user_id")
	private UUID appUserId;

	@Column(name = "source_filename", nullable = false)
	private String sourceFilename;

	@Column(name = "profile_type", nullable = false)
	private String profileType;

	@Column(name = "parsed_profile", nullable = false, columnDefinition = "TEXT")
	private String parsedProfile;

	@Column(name = "confirmed_profile", columnDefinition = "TEXT")
	private String confirmedProfile;

	@Column(nullable = false)
	private String status = "DRAFT";

	@Column(name = "confirmed_at")
	private Instant confirmedAt;

	@Column(name = "consent_accepted_at")
	private Instant consentAcceptedAt;

	@Column(name = "redaction_summary", nullable = false, columnDefinition = "TEXT")
	private String redactionSummary = "{}";

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ocr_summary", nullable = false)
	private String ocrSummary = "{}";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
