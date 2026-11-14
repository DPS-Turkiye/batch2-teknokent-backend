package com.dps.teknokent.security;

import org.jspecify.annotations.Nullable;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/** The Moodle site's public keys, as a source the launch verifier reads from; empty when LTI is not set up. */
public record LtiPlatformKeys(@Nullable JWKSource<SecurityContext> source) {

	public static LtiPlatformKeys of(JWKSource<SecurityContext> source) {
		return new LtiPlatformKeys(source);
	}

	public static LtiPlatformKeys unavailable() {
		return new LtiPlatformKeys(null);
	}
}
