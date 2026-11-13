package com.dps.teknokent.domain;

public enum UserAccountStatus {
	PENDING,
	APPROVED,
	REJECTED,
	/** A tombstone left by an admin's deletion: the row holds no personal data and nobody can sign in as it. */
	DELETED
}
