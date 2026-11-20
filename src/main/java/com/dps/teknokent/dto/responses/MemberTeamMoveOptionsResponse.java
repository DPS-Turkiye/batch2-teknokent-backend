package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.TeamMoveBlock;

/** What the move dialog shows: where the person is, why they cannot move if they cannot, and where they may go. */
public record MemberTeamMoveOptionsResponse(
		UUID userId,
		String fullName,
		@Nullable TeamMoveTeamResponse currentTeam,
		@Nullable TeamMoveBlock blockedBy,
		List<TeamMoveTeamResponse> targets) {
}
