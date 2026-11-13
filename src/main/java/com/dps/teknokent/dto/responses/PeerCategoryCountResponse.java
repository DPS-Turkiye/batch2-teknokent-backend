package com.dps.teknokent.dto.responses;

import com.dps.teknokent.domain.PeerTopicCategory;

/** How many of the program's peer mentors offer an active category (the Keşfet category cards). */
public record PeerCategoryCountResponse(PeerTopicCategory category, int mentorCount) {
}
