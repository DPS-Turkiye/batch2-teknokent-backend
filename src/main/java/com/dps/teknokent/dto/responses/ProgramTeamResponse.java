package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.TeamJoinStatus;

/**
 * One row of the program's Takımlar tab. {@code inviteId} is null for a team that joined the program without a
 * code (the older signup path); {@code organizationId} is null until the lead signs up.
 * {@code codeSendFailed}: SES refused the last code email, so the team shows under "Sorunlu".
 */
public record ProgramTeamResponse(
		@Nullable UUID inviteId,
		UUID organizationId,
		String name,
		TeamJoinStatus status,
		@Nullable String leadName,
		@Nullable String leadEmail,
		String code,
		Instant invitedAt,
		Instant joinedAt,
		int memberCount,
		@Nullable Instant codeSentAt,
		boolean codeSendFailed) {
}
