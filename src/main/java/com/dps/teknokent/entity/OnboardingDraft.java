package com.dps.teknokent.entity;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "onboarding_draft")
@Getter
@Setter
@NoArgsConstructor
public class OnboardingDraft {

	public static OnboardingDraft forUser(String firebaseUid) {
		OnboardingDraft draft = new OnboardingDraft();
		draft.firebaseUid = firebaseUid;
		return draft;
	}

	@Id
	@Column(name = "firebase_uid", nullable = false, length = 128)
	private String firebaseUid;

	@Column(nullable = false, length = 64)
	private String kind;

	@Column(nullable = false, length = 64)
	private String step;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb", nullable = false)
	private String payload;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;
}
