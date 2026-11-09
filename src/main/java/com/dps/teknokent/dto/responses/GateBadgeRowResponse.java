package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.BadgeSource;
import com.dps.teknokent.domain.GateBadgeState;

/**
 * A person and where they stand with a program's gate badge, for staff: Katılımcılar's roster and the admin's
 * student list. {@code awardId} is the latest award (the one to revoke when ACTIVE); null when never awarded.
 * {@code training} shows how far someone without the badge got in the training ("Eğitimde").
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
		String reason,
		Training training) {

	/** The person's progress on the program's current training version (4T); NONE without a training. */
	public record Training(boolean videoWatched, int attempts, Integer bestScore) {
		public static final Training NONE = new Training(false, 0, null);

		public boolean started() {
			return videoWatched || attempts > 0;
		}
	}
}
