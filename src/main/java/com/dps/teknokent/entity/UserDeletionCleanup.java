package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.UserDeletionCleanupKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing a committed user deletion still has to remove outside the database. The target (a Firebase uid or a
 * storage key, which carries the uploaded file's name) is kept only until it is gone; then the row is deleted.
 */
@Entity
@Table(name = "user_deletion_cleanup")
@Getter
@Setter
@NoArgsConstructor
public class UserDeletionCleanup {

	@Id
	private UUID id;

	@Column(name = "audit_id", nullable = false, updatable = false)
	private UUID auditId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 32)
	private UserDeletionCleanupKind kind;

	@Column(nullable = false, updatable = false, length = 512)
	private String target;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "last_error")
	private String lastError;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	public UserDeletionCleanup(UUID auditId, UserDeletionCleanupKind kind, String target, Instant now) {
		this.id = UUID.randomUUID();
		this.auditId = auditId;
		this.kind = kind;
		this.target = target;
		this.nextAttemptAt = now;
		this.createdAt = now;
	}
}
