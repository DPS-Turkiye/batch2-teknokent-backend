package com.dps.teknokent.security.institution;

import java.util.Map;
import java.util.Set;

import com.dps.teknokent.domain.UserRole;

/**
 * One institution's own sign-in (SAML, CAS, OIDC or plain OAuth; ODTÜ's protocol is not known yet). Everything after it is
 * shared: {@code InstitutionLoginService} links the account and hands the browser a Firebase custom token.
 */
public interface InstitutionLoginProvider {

	/** The institution's key: its name in institution_person and the prefix of the Firebase uids it creates. */
	String id();

	/** Where to send the browser to sign in; a path starting with "/" is on the Menton frontend. */
	String startLogin(String state);

	/**
	 * Checks what the institution sent back and says who signed in; a 400 when it does not hold up. {@code state} is
	 * the one {@link #startLogin} was given, already checked and spent by the caller.
	 */
	InstitutionIdentity completeLogin(String state, Map<String, String> callback);

	/**
	 * Whether the login's record is itself the approval (ODTÜ: a student is a MENTEE). A login that only proves who
	 * someone is (OTG) answers false: it never creates an account or grants anything, and a newcomer goes through
	 * Menton's own sign-up and coordinator approval like everyone else.
	 */
	default boolean grantsAccess() {
		return true;
	}

	/** Permissions beyond what the record gives. Only the dev stub has any: it stands in for badges until they exist. */
	default Set<UserRole> extraGrants(String persistentId) {
		return Set.of();
	}
}
