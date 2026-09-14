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
