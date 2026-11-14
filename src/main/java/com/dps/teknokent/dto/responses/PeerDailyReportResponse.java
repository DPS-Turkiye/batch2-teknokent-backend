package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PeerReportStatus;

/**
 * One day of "Faaliyet Raporum": the day's meetings (from the calendar) and activities, the mentor's answers, and
 * what the side cards need. {@code recordedMinutes} is informational only, never an official working time.
 */
public record PeerDailyReportResponse(
		LocalDate date,
		@Nullable UUID id,
		@Nullable PeerReportStatus status,
		boolean editable,
		Instant deadline,
		boolean late,
		List<PeerReportMeetingResponse> meetings,
		List<PeerActivityResponse> activities,
		@Nullable String didToday,
		@Nullable String challenge,
		Set<PeerMentorSkill> skills,
		@Nullable String learned,
		@Nullable String tomorrowPlan,
		Set<PeerMentorSkill> suggestedSkills,
		int recordedMinutes,
		int missingMeetingReports,
		@Nullable Instant submittedAt,
		@Nullable String revisionNote,
		@Nullable Instant updatedAt) {
}
