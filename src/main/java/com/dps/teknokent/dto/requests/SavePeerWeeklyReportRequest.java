package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.Size;

/** The three "Haftalık değerlendirme" answers, as a draft. */
public record SavePeerWeeklyReportRequest(
		UUID actorId,
		@Size(max = 4000) String needsObserved,
		@Size(max = 4000) String growth,
		@Size(max = 4000) String nextWeekPlan) {
}
