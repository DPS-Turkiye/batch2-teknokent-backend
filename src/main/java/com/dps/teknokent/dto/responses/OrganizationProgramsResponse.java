package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

/**
 * The programs a startup has been assigned to, and which one it has made active.
 *
 * <p>Deliberately not {@link ProgramResponse}: a startup has no business seeing
 * the other startups or the mentor roster of a program it merely belongs to.
 *
 * <p>{@code activeProgramId} is null when the startup has no assignment, or has
 * one but has not chosen yet — both render as the "no program" state.
 *
 * <p>{@code individualMentees}: the program's mentees are students who joined as a team of one, so their screens
 * drop the startup pages and wording.
 */
public record OrganizationProgramsResponse(
		List<OrganizationProgramResponse> programs,
		UUID activeProgramId) {

	public record OrganizationProgramResponse(
			UUID id,
			String name,
			String coordinatorName,
			boolean individualMentees) {
	}
}
