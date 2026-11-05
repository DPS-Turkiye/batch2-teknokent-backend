package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.TeamExitReason;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.TeamMembership;

/** A person's past and current startups, newest first; {@code leftAt} is null for the current one. */
public record TeamHistoryResponse(UUID userId, String fullName, List<Stay> stays) {

	public record Stay(UUID organizationId, String organizationName, Instant joinedAt, Instant leftAt,
			TeamExitReason exitReason, UserAccountStatus startupApproval, boolean startupArchived) {
	}

	public static TeamHistoryResponse of(AppUser person, List<TeamMembership> stays) {
		return new TeamHistoryResponse(person.getId(), person.getFullName(), stays.stream()
				.map(stay -> {
					Organization startup = stay.getOrganization();
					return new Stay(startup.getId(), startup.getName(), stay.getJoinedAt(), stay.getLeftAt(),
							stay.getExitReason(), startup.getApprovalStatus(), startup.getArchivedAt() != null);
				})
				.toList());
	}
}
