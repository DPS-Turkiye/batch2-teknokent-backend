package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.TeamPersonRole;

/**
 * Takımlar's person search: the people of the program's teams whose name matches, each with their team.
 * {@code truncated}: more people matched than the list holds, so the coordinator should narrow the search.
 */
public record ProgramTeamPeopleResponse(List<Person> people, boolean truncated) {

	public record Person(
			UUID id,
			String fullName,
			TeamPersonRole role,
			@Nullable String jobTitle,
			UUID organizationId,
			String teamName) {
	}
}
