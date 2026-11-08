package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/** An institution login (e.g. ODTÜ) bound to one Menton account; a re-login with the same id lands on the same row. */
@Entity
@Table(name = "app_user_identity", uniqueConstraints = {
		@UniqueConstraint(name = "uq_app_user_identity", columnNames = { "provider", "persistent_id" }),
		@UniqueConstraint(name = "uq_app_user_identity_user", columnNames = { "user_id", "provider" }) })
@Getter
@Setter
@NoArgsConstructor
public class AppUserIdentity {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private AppUser user;

	@Column(nullable = false, length = 16)
	private String provider;

	@Column(name = "persistent_id", nullable = false, length = 120)
	private String persistentId;

	@Column(name = "linked_at", nullable = false)
	private Instant linkedAt;

	@Column(name = "last_login_at", nullable = false)
	private Instant lastLoginAt;

	public AppUserIdentity(AppUser user, String provider, String persistentId, Instant now) {
		this.user = user;
		this.provider = provider;
		this.persistentId = persistentId;
		this.linkedAt = now;
		this.lastLoginAt = now;
	}
}
