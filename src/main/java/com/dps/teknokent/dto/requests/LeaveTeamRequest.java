package com.dps.teknokent.dto.requests;

import java.util.UUID;

/** The leader leaving; {@code successorId} is required unless nobody else is in the team. */
public record LeaveTeamRequest(UUID actorId, UUID successorId) {
}
