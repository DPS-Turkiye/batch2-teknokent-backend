package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The Takımlar drawer: the team lead, the code and joined members, and the team's mentorships in the program. */
public record ProgramTeamDetailResponse(
		UUID organizationId,
		String name,
		String sector,
		Person lead,
		String code,
		List<Person> members,
		List<CoordinatorMentorshipResponse> mentorships) {

	public record Person(UUID id, String name, String jobTitle, String email, Instant joinedAt) {
	}
}
