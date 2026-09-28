package com.dps.teknokent.security;

public record FirebaseIdentity(String uid, String email, boolean emailVerified) {
}
