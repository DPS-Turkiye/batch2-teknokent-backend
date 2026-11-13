package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.PeerDocumentVisibility;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** A portfolio or project link for the "Portfolyo ve bağlantılar" card. */
public record CreatePeerMentorLinkRequest(
		UUID actorId,
		@NotBlank @Size(max = 500) @Pattern(regexp = "^https?://\\S+$", message = "must be a http(s) link") String url,
		@NotNull PeerDocumentVisibility visibility) {
}
