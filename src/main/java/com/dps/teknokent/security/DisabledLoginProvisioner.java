package com.dps.teknokent.security;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

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
	public Map<String, Boolean> passwordsSet(Collection<String> uids) {
		return Map.of();
	}

	@Override
	public void revokeSignIns(String email) {
		// Without Firebase there are no Firebase sign-ins; the recorded time still ends app sessions.
	}
}
