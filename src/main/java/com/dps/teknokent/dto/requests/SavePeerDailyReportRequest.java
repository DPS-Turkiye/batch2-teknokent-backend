package com.dps.teknokent.dto.requests;

import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.PeerMentorSkill;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The "Günlük değerlendirme" answers of one day, as a draft. */
public record SavePeerDailyReportRequest(
		UUID actorId,
		@Size(max = 4000) String didToday,
		@Size(max = 4000) String challenge,
		@Size(max = 10) Set<@NotNull PeerMentorSkill> skills,
		@Size(max = 4000) String learned,
		@Size(max = 4000) String tomorrowPlan) {
}
