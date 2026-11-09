package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.GateBadgeState;

/**
 * A person and where they stand with a program's gate badge, for staff: Katılımcılar's roster and the admin's
 * student list. {@code awardId} is the latest award (the one to revoke when ACTIVE); null when never awarded.
 */
public record GateBadgeRowResponse(
		UUID userId,
		String fullName,
		String email,
		UUID programId,
		String programName,
		GateBadgeState state,
		UUID awardId,
		BadgeSource source,
		Instant awardedAt,
		Instant revokedAt,
		String reason) {
}
