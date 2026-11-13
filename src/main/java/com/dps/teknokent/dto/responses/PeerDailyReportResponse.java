package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.dps.teknokent.domain.PeerMentorSkill;
import com.dps.teknokent.domain.PeerReportStatus;

/**
 * One day of "Faaliyet Raporum": the day's meetings (from the calendar) and activities, the mentor's answers, and
 * what the side cards need. {@code recordedMinutes} is informational only, never an official working time.
 */
public record PeerDailyReportResponse(
		LocalDate date,
		UUID id,
		PeerReportStatus status,
		boolean editable,
		Instant deadline,
		boolean late,
		List<PeerReportMeetingResponse> meetings,
		List<PeerActivityResponse> activities,
		String didToday,
		String challenge,
		Set<PeerMentorSkill> skills,
		String learned,
		String tomorrowPlan,
		Set<PeerMentorSkill> suggestedSkills,
		int recordedMinutes,
		int missingMeetingReports,
		Instant submittedAt,
		String revisionNote,
		Instant updatedAt) {
}
