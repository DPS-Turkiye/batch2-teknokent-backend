package com.dps.teknokent.security;

import java.util.Optional;

import com.dps.teknokent.config.FirebaseApps;
import com.dps.teknokent.config.FirebaseProperties;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;

public class FirebaseAdminTokenVerifier implements FirebaseTokenVerifier {

	private final FirebaseAuth firebaseAuth;

	public FirebaseAdminTokenVerifier(FirebaseProperties properties) {
		this.firebaseAuth = FirebaseAuth.getInstance(FirebaseApps.named(properties));
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Optional<FirebaseIdentity> verify(String idToken) {
		try {
			FirebaseToken decoded = firebaseAuth.verifyIdToken(idToken);
			String email = decoded.getEmail();
			if (email == null || email.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(new FirebaseIdentity(decoded.getUid(), email));
		} catch (FirebaseAuthException | IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
