package com.dps.teknokent.security;

import java.util.Optional;

public class DisabledFirebaseTokenVerifier implements FirebaseTokenVerifier {

	@Override
	public boolean enabled() {
		return false;
	}

	@Override
	public Optional<FirebaseIdentity> verify(String idToken) {
		return Optional.empty();
	}
}
