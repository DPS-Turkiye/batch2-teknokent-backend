package com.dps.teknokent.security;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.dps.teknokent.exception.InvalidRequestException;

public final class CurrentActor {

	private CurrentActor() {
	}

	// A verified bearer token always wins; the actorId is only trusted on the
	// shared service credentials the Next.js proxy uses.
	/**
	 * Like {@link #resolve}, but null instead of an error when nobody is named: for reads that only narrow what a
	 * known caller sees. A signed-in caller is always themselves.
	 */
	public static UUID resolveOptional(UUID requestedActorId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
			return principal.id();
		}
		return requestedActorId;
	}

	/**
	 * The Firebase uid behind a bearer token, for /api/onboarding paths that run before the account is approved
	 * (or exists). The proxy's shared credentials never carry one, so they cannot act for anybody here.
	 */
	public static String firebaseUid() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof FirebaseIdentity identity) {
			return identity.uid();
		}
		if (authentication != null
				&& authentication.getPrincipal() instanceof AppUserPrincipal principal
				&& principal.firebaseUid() != null
				&& !principal.firebaseUid().isBlank()) {
			return principal.firebaseUid();
		}
		throw new InvalidRequestException("A Firebase session is required.");
	}

	public static UUID resolve(UUID requestedActorId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof AppUserPrincipal principal) {
			return principal.id();
		}
		if (requestedActorId == null) {
			throw new InvalidRequestException("actorId is required");
		}
		return requestedActorId;
	}
}
