package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The program's whole active set; an empty list turns every category off. */
public record ReplaceProgramPeerCategoriesRequest(
		UUID actorId,
		@NotNull @Size(max = 8) List<@NotNull PeerTopicCategory> categories) {
}
