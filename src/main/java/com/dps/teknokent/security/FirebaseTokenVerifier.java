package com.dps.teknokent.security;

import java.util.Optional;

public interface FirebaseTokenVerifier {

	boolean enabled();

	Optional<FirebaseIdentity> verify(String idToken);
}
