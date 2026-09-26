package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.TeamJoinStatus;

/**
 * One row of the program's Takımlar tab. {@code inviteId} is null for a team that joined the program without a
 * code (the older signup path); {@code organizationId} is null until the lead signs up.
 */
public record ProgramTeamResponse(
		UUID inviteId,
		UUID organizationId,
		String name,
		TeamJoinStatus status,
		String leadName,
		String leadEmail,
		String code,
		Instant invitedAt,
		Instant joinedAt,
		int memberCount) {
}
