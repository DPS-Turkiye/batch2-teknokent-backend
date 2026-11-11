package com.dps.teknokent.domain;

/**
 * Menton's badge catalogue. Adding one means a value here, its check in {@code user_badge.badge_code} and its
 * texts. Achievements are awarded by rules (a later phase); the gate badge by its training (a later phase) or
 * by hand.
 */
public enum BadgeCode {
	PEER_MENTOR(BadgeKind.GATE, UserRole.MENTOR),
	FIRST_SESSION(BadgeKind.ACHIEVEMENT, null),
	SESSIONS_10(BadgeKind.ACHIEVEMENT, null),
	HOURS_25(BadgeKind.ACHIEVEMENT, null),
	WELL_RATED(BadgeKind.ACHIEVEMENT, null),
	ACTIVE_MENTEE(BadgeKind.ACHIEVEMENT, null);

	private final BadgeKind kind;
	private final UserRole grants;

	BadgeCode(BadgeKind kind, UserRole grants) {
		this.kind = kind;
		this.grants = grants;
	}

	public BadgeKind kind() {
		return kind;
	}

	/** The permission an active award gives, or null for an achievement. */
	public UserRole grants() {
		return grants;
	}
}
