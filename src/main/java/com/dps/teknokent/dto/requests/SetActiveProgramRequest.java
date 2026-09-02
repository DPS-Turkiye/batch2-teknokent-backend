package com.dps.teknokent.dto.requests;

import java.util.UUID;

/**
 * {@code programId} is intentionally nullable and unvalidated: a null clears the
 * choice, which is how a startup goes back to the "no program" state.
 */
public record SetActiveProgramRequest(
		UUID programId,
		UUID actorId) {
}
