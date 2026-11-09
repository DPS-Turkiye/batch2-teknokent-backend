package com.dps.teknokent.domain;

/** Where a person stands with a program's gate badge: never awarded, or the status of the latest award. */
public enum GateBadgeState {
	NONE,
	ACTIVE,
	EXPIRED,
	REVOKED;

	public static GateBadgeState of(BadgeStatus status) {
		return status == null ? NONE : valueOf(status.name());
	}
}
