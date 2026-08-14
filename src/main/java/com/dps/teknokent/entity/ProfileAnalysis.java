package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

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

	@Id
	private UUID id;

	@Column(name = "organization_id", nullable = false)
	private UUID organizationId;

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

	@Column(name = "ocr_summary", nullable = false, columnDefinition = "TEXT")
	private String ocrSummary = "{}";

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;
}
