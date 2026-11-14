package com.dps.teknokent.domain;

import org.jspecify.annotations.Nullable;

/** Where a person stands with a program's gate badge: never awarded, or the status of the latest award. */
public enum GateBadgeState {
	NONE,
	ACTIVE,
	EXPIRED,
	REVOKED;

	public static GateBadgeState of(@Nullable BadgeStatus status) {
		return status == null ? NONE : valueOf(status.name());
	}
}
