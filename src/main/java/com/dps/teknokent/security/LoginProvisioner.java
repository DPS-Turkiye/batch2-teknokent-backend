package com.dps.teknokent.security;

import java.util.Optional;

/** Finds or creates the Firebase login for an address, so an admin can hand someone an account. */
public interface LoginProvisioner {

	/** A Firebase login for {@code email}, creating one without a password when there is none. */
	LoginProvisioning loginFor(String email, String displayName);

	record LoginProvisioning(String uid, boolean created) {
	}

	/**
	 * A Firebase link that lets the owner of {@code email} set a new password, returning to {@code continueUrl}
	 * afterwards; empty when no link can be made.
	 */
	Optional<String> passwordSetupLink(String email, String continueUrl);

	/** A Firebase link that verifies ownership of {@code email}, returning to {@code continueUrl} afterwards. */
	Optional<String> emailVerificationLink(String email, String continueUrl);
}
