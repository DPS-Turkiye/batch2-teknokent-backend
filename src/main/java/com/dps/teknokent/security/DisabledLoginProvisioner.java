package com.dps.teknokent.security;

import com.dps.teknokent.exception.InvalidRequestException;

public class DisabledLoginProvisioner implements LoginProvisioner {

	@Override
	public String loginFor(String email, String displayName) {
		throw new InvalidRequestException("Firebase is not configured, so no login can be created.");
	}
}
