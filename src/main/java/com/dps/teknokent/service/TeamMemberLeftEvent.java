package com.dps.teknokent.service;

import java.util.UUID;

/** Published when someone leaves a team; their open tasks are handed over once the leave is committed. */
public record TeamMemberLeftEvent(UUID organizationId, UUID leaverId) {
}
