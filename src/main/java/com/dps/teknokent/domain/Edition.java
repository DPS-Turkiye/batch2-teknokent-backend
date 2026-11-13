package com.dps.teknokent.domain;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which of the two deployments this backend is: Teknokent's startup mentoring, or ODTÜ's office hours and İŞKUR
 * peer mentoring. Both run the same code against their own database; the edition closes the other side's
 * endpoints and program types so neither deployment serves what belongs to the other.
 */
public enum Edition {

	TEKNOKENT(
			EnumSet.of(ProgramType.STARTUP_MENTORING),
			List.of(
					"/api/admin/programs/*/startups/*/move",
					"/api/admin/users/*/delete",
					"/api/assistant/**",
					"/api/auth/institution/otg/**",
					"/api/auth/register/mentor/**",
					"/api/auth/register/member/**",
					"/api/bottlenecks/**",
					"/api/meetings/*/assistant-share/**",
					"/api/organizations/*/bottlenecks/**",
					"/api/organizations/*/progress/**")),

	ODTU(
			EnumSet.of(ProgramType.PEER_MENTORING, ProgramType.OFFICE_HOURS),
			List.of(
					"/api/peer-mentoring/**",
					"/api/student/**",
					"/api/training/**",
					"/api/badges/**",
					"/api/institution/**",
					"/api/auth/institution/odtu/**",
					"/api/admin/academics/**",
					"/api/admin/students/**",
					"/api/lti/**",
					"/api/office-hours/**",
					"/api/coordinator/programs/*/academics/**",
					"/api/coordinator/programs/*/badges/**",
					"/api/coordinator/programs/*/peer-categories/**",
					"/api/coordinator/programs/*/training/**",
					"/api/users/*/badges/**"));

	private final Set<ProgramType> programTypes;
	private final List<String> ownPaths;

	Edition(Set<ProgramType> programTypes, List<String> ownPaths) {
		this.programTypes = programTypes;
		this.ownPaths = ownPaths;
	}

	public boolean allows(ProgramType type) {
		return type != null && programTypes.contains(type);
	}

	/** Path patterns only this edition serves; every other edition answers 404 for them. */
	public List<String> ownPaths() {
		return ownPaths;
	}

	/** Every path pattern that belongs to another edition, so this one must not serve it. */
	public List<String> closedPaths() {
		return EnumSet.complementOf(EnumSet.of(this)).stream()
				.flatMap(other -> other.ownPaths.stream())
				.toList();
	}

	/**
	 * A blank or unknown value stops the startup: guessing an edition would quietly open the other side's
	 * endpoints on a deployment that was meant to have them closed.
	 */
	public static Edition parse(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("app.edition (APP_EDITION) is blank; set it to TEKNOKENT or ODTU.");
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			throw new IllegalStateException(
					"app.edition (APP_EDITION) must be TEKNOKENT or ODTU, not '" + value.trim() + "'.", exception);
		}
	}
}
