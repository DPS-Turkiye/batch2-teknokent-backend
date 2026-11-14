package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.PeerDocumentVisibility;

import jakarta.validation.constraints.NotNull;

/** Changes who sees a document ("Yalnızca doğrulama için" / "Profilimde göster"). */
public record UpdatePeerMentorDocumentRequest(UUID actorId, @NotNull PeerDocumentVisibility visibility) {
}
