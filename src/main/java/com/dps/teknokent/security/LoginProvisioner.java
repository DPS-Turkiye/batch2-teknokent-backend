package com.dps.teknokent.security;

import java.util.Optional;

/** Finds or creates the Firebase login for an address, so an admin can hand someone an account. */
public interface LoginProvisioner {

	/** The Firebase uid for {@code email}, creating a login without a password when there is none. */
	String loginFor(String email, String displayName);

	/**
	 * A Firebase link that lets the owner of {@code email} set a new password, returning to {@code continueUrl}
	 * afterwards; empty when no link can be made.
	 */
	Optional<String> passwordSetupLink(String email, String continueUrl);

	/** Whether the login for {@code uid} has a password yet; empty when Firebase cannot say. */
	Optional<Boolean> hasPassword(String uid);
}
