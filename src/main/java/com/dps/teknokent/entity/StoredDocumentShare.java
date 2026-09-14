package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A startup document made visible to one of its mentors. */
@Entity
@Table(name = "stored_document_share")
@Getter
@Setter
@NoArgsConstructor
public class StoredDocumentShare {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "document_id", nullable = false)
	private UUID documentId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "mentor_id", nullable = false)
	private AppUser mentor;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shared_by_id", nullable = false)
	private AppUser sharedBy;

	@CreationTimestamp
	@Column(name = "shared_at", nullable = false, updatable = false)
	private Instant sharedAt;
}
