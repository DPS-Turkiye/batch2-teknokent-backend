package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.PermissionSource;
import com.dps.teknokent.domain.PermissionStatus;
import com.dps.teknokent.domain.UserRole;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per account and permission; a grant changes status in place rather than being deleted. */
@Entity
@Table(name = "app_user_permission",
		uniqueConstraints = @UniqueConstraint(name = "uq_app_user_permission", columnNames = { "user_id", "permission" }))
@Getter
@Setter
@NoArgsConstructor
public class AppUserPermission {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private UserRole permission;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PermissionStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private PermissionSource source;

	@Column(name = "source_ref")
	private UUID sourceRef;

	@Column(name = "granted_at", nullable = false)
	private Instant grantedAt;

	@Column(name = "granted_by")
	private UUID grantedBy;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private UUID revokedBy;

	AppUserPermission(AppUser user, UserRole permission, PermissionSource source, Instant now) {
		this.user = user;
		this.permission = permission;
		this.source = source;
		this.status = PermissionStatus.ACTIVE;
		this.grantedAt = now;
	}

	boolean activeAt(Instant now) {
		return status == PermissionStatus.ACTIVE && (expiresAt == null || now.isBefore(expiresAt));
	}

	void reactivate(PermissionSource newSource, Instant now) {
		status = PermissionStatus.ACTIVE;
		source = newSource;
		grantedAt = now;
		revokedAt = null;
		revokedBy = null;
		expiresAt = null;
	}

	void revoke(Instant now) {
		status = PermissionStatus.REVOKED;
		revokedAt = now;
	}
}
