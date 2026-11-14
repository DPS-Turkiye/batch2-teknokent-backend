package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** {@code PUT /api/peer-mentoring/profile/draft}: the acting student and their unfinished wizard. */
public record SavePeerMentorProfileDraftRequest(UUID actorId, @NotNull @Valid PeerMentorProfileDraft draft) {
}
