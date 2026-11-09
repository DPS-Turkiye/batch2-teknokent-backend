package com.dps.teknokent.security;

/** {@code emailVerified} is Firebase's {@code email_verified} claim: whether the address was proven to belong to the login. */
public record FirebaseIdentity(String uid, String email, boolean emailVerified) {
}
