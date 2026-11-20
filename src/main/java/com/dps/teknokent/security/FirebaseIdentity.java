package com.dps.teknokent.security;

import java.time.Instant;

import org.jspecify.annotations.Nullable;

/**
 * {@code emailVerified} is Firebase's {@code email_verified} claim: whether the address was proven to belong to the
 * login. {@code signedInAt} is its {@code auth_time} claim: when the person last entered their credentials (a token
 * refresh does not move it); null when the token carries none.
 */
public record FirebaseIdentity(String uid, String email, boolean emailVerified, @Nullable Instant signedInAt) {

	public FirebaseIdentity(String uid, String email, boolean emailVerified) {
		this(uid, email, emailVerified, null);
	}
}
