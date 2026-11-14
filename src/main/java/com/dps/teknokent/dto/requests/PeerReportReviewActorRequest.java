package com.dps.teknokent.dto.requests;

import java.util.UUID;

/** A review decision with nothing but the actor (approve). */
public record PeerReportReviewActorRequest(UUID actorId) {
}
