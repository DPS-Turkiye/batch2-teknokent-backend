package com.dps.teknokent.dto.requests;

import java.util.UUID;

/** Ticks an action step of a meeting report on or off. */
public record UpdatePeerReportActionRequest(UUID actorId, boolean done) {
}
