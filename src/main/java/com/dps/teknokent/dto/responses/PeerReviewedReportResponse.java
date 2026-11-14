package com.dps.teknokent.dto.responses;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.PeerReportKind;

/** A sent report as its program's coordinator reads it: exactly one of the three report bodies is set. */
public record PeerReviewedReportResponse(
		PeerReportKind kind,
		UUID id,
		UUID programId,
		Mentor mentor,
		@Nullable PeerMeetingReportResponse meeting,
		@Nullable PeerDailyReportResponse daily,
		@Nullable PeerWeeklyReportResponse weekly) {

	public record Mentor(UUID id, String fullName) {
	}
}
