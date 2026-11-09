package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerTopicCategory;

/** A peer-mentoring program's active categories, in the enum's order. */
public record ProgramPeerCategoriesResponse(UUID programId, List<PeerTopicCategory> active) {
}
