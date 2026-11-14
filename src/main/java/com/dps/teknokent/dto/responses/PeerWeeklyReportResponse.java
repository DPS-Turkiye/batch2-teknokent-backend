package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.PeerReportStatus;

/**
 * The weekly summary: computed while a draft, the frozen snapshot once sent. It can be sent from Friday evening
 * ({@code opensAt}) to Monday night ({@code deadline}), when every held meeting has a sent report and the three answers
 * are filled in. {@code dailyRequired}/{@code dailySent} are shown, not required.
 */
public record PeerWeeklyReportResponse(
		LocalDate weekStart,
		@Nullable UUID id,
		@Nullable PeerReportStatus status,
		boolean editable,
		Instant opensAt,
		Instant deadline,
		PeerWeeklySummary summary,
		List<PeerReportWeekResponse.Day> days,
		int dailyRequired,
		int dailySent,
		@Nullable String needsObserved,
		@Nullable String growth,
		@Nullable String nextWeekPlan,
		@Nullable Instant submittedAt,
		@Nullable String revisionNote,
		@Nullable Instant updatedAt) {
}
