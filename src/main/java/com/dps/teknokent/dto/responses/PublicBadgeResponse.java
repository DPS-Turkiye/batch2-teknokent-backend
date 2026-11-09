package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.BadgeCode;
import com.dps.teknokent.domain.BadgeKind;
import com.dps.teknokent.entity.UserBadge;

/** An active badge as anyone who can see the person sees it (D5); never who awarded it or why. */
public record PublicBadgeResponse(BadgeCode code, BadgeKind kind, UUID programId, Instant awardedAt) {

	public static PublicBadgeResponse of(UserBadge award) {
		return new PublicBadgeResponse(award.getBadgeCode(), award.getBadgeCode().kind(), award.getProgramId(),
				award.getAwardedAt());
	}
}
