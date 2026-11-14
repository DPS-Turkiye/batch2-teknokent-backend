package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Ticks an action step of a meeting report on or off; a body without {@code done} would silently untick it. */
public record UpdatePeerReportActionRequest(UUID actorId, @NotNull Boolean done) {
}
