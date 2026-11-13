package com.dps.teknokent.security;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.exception.InvalidRequestException;

public class DisabledLoginProvisioner implements LoginProvisioner {

	@Override
	public String loginFor(String email, String displayName) {
		throw new InvalidRequestException("Firebase is not configured, so no login can be created.");
	}

	@Override
	public Optional<String> passwordSetupLink(String email, String continueUrl) {
		return Optional.empty();
	}

	@Override
	public Optional<String> passwordResetLink(String email, String continueUrl) {
		throw noFirebase();
	}

	@Override
	public String emailVerificationLink(String email, String continueUrl) {
		throw noFirebase();
	}

	// The frontend then falls back to Firebase's own e-mail.
	private static ResponseStatusException noFirebase() {
		return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Firebase is not configured.");
	}

	@Override
	public Map<String, Boolean> passwordsSet(Collection<String> uids) {
		return Map.of();
	}

	@Override
	public String customToken(String uid, String email, String displayName, boolean emailVerified) {
		throw noFirebase();
	}

	// Without Firebase there is no login to find or delete.
	@Override
	public Optional<String> uidForEmail(String email) {
		return Optional.empty();
	}

	@Override
	public void deleteLogin(String uid) {
	}

	@Override
	public void revokeSignIns(String email) {
		// Without Firebase there are no Firebase sign-ins; the recorded time still ends app sessions.
	}
}
