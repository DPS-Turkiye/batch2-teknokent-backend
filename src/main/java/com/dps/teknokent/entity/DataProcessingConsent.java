package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.ProcessingConsentPurpose;
import com.dps.teknokent.domain.ProcessingConsentSource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One consent a person gave, kept as proof: who gave it, whom it is about, for what, to which text version and when.
 * Rows are never changed. A person still signing up has no account yet, so they are named by their Firebase uid.
 */
@Entity
@Table(name = "data_processing_consent")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataProcessingConsent {

	@Id
	private UUID id;

	@Column(name = "consenter_user_id", updatable = false)
	private UUID consenterUserId;

	@Column(name = "consenter_firebase_uid", length = 128, updatable = false)
	private String consenterFirebaseUid;

	@Column(name = "subject_user_id", updatable = false)
	private UUID subjectUserId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32, updatable = false)
	private ProcessingConsentPurpose purpose;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32, updatable = false)
	private ProcessingConsentSource source;

	@Column(name = "text_version", nullable = false, length = 64, updatable = false)
	private String textVersion;

	@Column(name = "accepted_at", nullable = false, updatable = false)
	private Instant acceptedAt;

	public static DataProcessingConsent of(
			UUID consenterUserId,
			String consenterFirebaseUid,
			UUID subjectUserId,
			ProcessingConsentPurpose purpose,
			ProcessingConsentSource source,
			String textVersion,
			Instant acceptedAt) {
		DataProcessingConsent consent = new DataProcessingConsent();
		consent.id = UUID.randomUUID();
		consent.consenterUserId = consenterUserId;
		consent.consenterFirebaseUid = consenterFirebaseUid;
		consent.subjectUserId = subjectUserId;
		consent.purpose = purpose;
		consent.source = source;
		consent.textVersion = textVersion;
		consent.acceptedAt = acceptedAt;
		return consent;
	}
}
