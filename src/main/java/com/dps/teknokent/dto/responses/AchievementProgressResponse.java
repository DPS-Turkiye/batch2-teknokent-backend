package com.dps.teknokent.dto.responses;

import java.time.Instant;

import com.dps.teknokent.domain.BadgeCode;

/**
 * One achievement badge as its holder sees it: earned or not, and how far along they are. {@code current} and
 * {@code target} count sessions, minutes or feedback depending on the badge; {@code average} is only set for
 * {@code WELL_RATED}, which also needs {@code averageTarget}.
 */
public record AchievementProgressResponse(BadgeCode code, boolean earned, Instant awardedAt, int current, int target,
		Double average, Double averageTarget) {
}
