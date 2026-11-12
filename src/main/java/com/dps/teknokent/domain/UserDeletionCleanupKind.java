package com.dps.teknokent.domain;

/** What a user deletion still has to remove outside the database. */
public enum UserDeletionCleanupKind {
	/** The target is a Firebase uid. */
	FIREBASE_LOGIN,
	/** The target is an object storage key. */
	STORED_OBJECT
}
