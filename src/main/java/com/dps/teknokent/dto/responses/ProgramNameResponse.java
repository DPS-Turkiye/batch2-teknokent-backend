package com.dps.teknokent.dto.responses;

import java.util.UUID;

/**
 * The least a signup picker needs: which programs exist, and what they are
 * called. Deliberately narrower than every other program response — a founder
 * who has not registered yet must not see coordinators, mentors or the other
 * startups in a program.
 * {@code individualMentees}: signup skips the startup questions, since each student joins as a team of one.
 */
public record ProgramNameResponse(
		UUID id,
		String name,
		boolean individualMentees) {
}
