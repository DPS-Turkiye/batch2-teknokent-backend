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

/**
 * Who deleted whom and when. Ids only, by design: no name, e-mail or other personal data may be added here, and
 * there are no foreign keys, so a row outlives the accounts it names.
 */
@Entity
@Table(name = "user_deletion_audit")
@Getter
@Setter
@NoArgsConstructor
public class UserDeletionAudit {

	@Id
	private UUID id;

	@Column(name = "deleted_user_id", nullable = false, updatable = false)
	private UUID deletedUserId;

	@Column(name = "deleted_by_id", nullable = false, updatable = false)
	private UUID deletedById;

	@Column(name = "deleted_at", nullable = false, updatable = false)
	private Instant deletedAt;

	/** Set once the Firebase login and every stored file are gone too. */
	@Column(name = "cleanup_completed_at")
	private Instant cleanupCompletedAt;

	public UserDeletionAudit(UUID deletedUserId, UUID deletedById, Instant deletedAt) {
		this.id = UUID.randomUUID();
		this.deletedUserId = deletedUserId;
		this.deletedById = deletedById;
		this.deletedAt = deletedAt;
	}
}
