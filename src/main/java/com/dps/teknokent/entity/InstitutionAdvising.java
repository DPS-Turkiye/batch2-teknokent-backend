package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A student ↔ academic advisor link from the institution's records; Menton never makes or edits these. */
@Entity
@Table(name = "institution_advising", uniqueConstraints = @UniqueConstraint(name = "uq_institution_advising",
		columnNames = { "institution", "student_persistent_id", "advisor_persistent_id" }))
@Getter
@Setter
@NoArgsConstructor
public class InstitutionAdvising {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false, length = 16)
	private String institution;

	@Column(name = "student_persistent_id", nullable = false, length = 120)
	private String studentPersistentId;

	@Column(name = "advisor_persistent_id", nullable = false, length = 120)
	private String advisorPersistentId;

	@Column(name = "synced_at", nullable = false)
	private Instant syncedAt;

	public InstitutionAdvising(String institution, String studentPersistentId, String advisorPersistentId,
			Instant now) {
		this.institution = institution;
		this.studentPersistentId = studentPersistentId;
		this.advisorPersistentId = advisorPersistentId;
		this.syncedAt = now;
	}
}
