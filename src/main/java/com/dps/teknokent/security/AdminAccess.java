package com.dps.teknokent.security;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.util.Texts;

/**
 * The one admin check for admin-only actions: an approved admin, or 403. A pending, rejected or deleted
 * account keeps its role but acts as nobody until it is approved again. Each service loads the actor its own way
 * (a missing actor is refused like any other caller).
 */
public final class AdminAccess {

	private static final String DEFAULT_TEXT = "error.adminOnly";

	private AdminAccess() {
	}

	public static AppUser requireAdmin(@Nullable AppUser actor) {
		return requireAdmin(actor, DEFAULT_TEXT);
	}

	/** With the service's own text, for a screen that tells the user more than "admins only". */
	public static AppUser requireAdmin(@Nullable AppUser actor, String textKey) {
		if (actor == null || actor.getStatus() != UserAccountStatus.APPROVED || !actor.has(UserRole.ADMIN)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, Texts.forRequest(textKey));
		}
		return actor;
	}
}
