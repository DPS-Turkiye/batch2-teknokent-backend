package com.dps.teknokent.security;

import java.util.Collection;
import java.util.Map;
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

	/** Whether each login has a password yet; a uid Firebase cannot answer for is left out. */
	Map<String, Boolean> passwordsSet(Collection<String> uids);

	/**
	 * Ends every Firebase sign-in of the login for {@code email}: its tokens stop working and it must sign in again.
	 * Looked up by email, not by a stored uid, which goes stale when a login is deleted and created again.
	 */
	void revokeSignIns(String email);
}
