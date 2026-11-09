package com.dps.teknokent.entity;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One award of a badge; kept after it is revoked or expires, as the record of who decided what and why. */
@Entity
@Table(name = "user_badge")
@Getter
@Setter
@NoArgsConstructor
public class UserBadge {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "badge_code", nullable = false, length = 48)
	private BadgeCode badgeCode;

	@Column(name = "program_id")
	private UUID programId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private BadgeStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private BadgeSource source;

	@Column(name = "awarded_at", nullable = false)
	private Instant awardedAt;

	@Column(name = "awarded_by")
	private UUID awardedBy;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by")
	private UUID revokedBy;

	@Column(columnDefinition = "TEXT")
	private String reason;
}
