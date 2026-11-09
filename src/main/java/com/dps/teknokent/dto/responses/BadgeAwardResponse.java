package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.BadgeStatus;
import com.dps.teknokent.entity.UserBadge;

/** An award as its holder (or staff) sees it, revoke reason included. */
public record BadgeAwardResponse(
		UUID id,
		BadgeCode code,
		BadgeKind kind,
		UUID programId,
		String programName,
		BadgeStatus status,
		BadgeSource source,
		Instant awardedAt,
		Instant expiresAt,
		Instant revokedAt,
		String reason) {

	public static BadgeAwardResponse of(UserBadge award, String programName) {
		return new BadgeAwardResponse(award.getId(), award.getBadgeCode(), award.getBadgeCode().kind(),
				award.getProgramId(), programName, award.getStatus(), award.getSource(), award.getAwardedAt(),
				award.getExpiresAt(), award.getRevokedAt(), award.getReason());
	}
}
