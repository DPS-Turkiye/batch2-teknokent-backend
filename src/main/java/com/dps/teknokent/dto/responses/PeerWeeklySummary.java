package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.PeerActivityType;
import com.dps.teknokent.domain.PeerMentorSkill;

/**
 * "Haftanın özeti", computed from the week's meeting reports and activities. Stored as JSON when the week is sent,
 * so its shape is part of the stored snapshot: add fields, don't rename them.
 */
public record PeerWeeklySummary(
		int meetings,
		int firstMeetings,
		int followUpMeetings,
		List<MenteeRef> mentees,
		int meetingReportsSent,
		int meetingReportsTotal,
		int activities,
		List<PeerActivityType> activityTypes,
		int recordedMinutes,
		List<SkillCount> skills,
		List<MenteeOutcome> outcomes) {

	public record MenteeRef(UUID id, String fullName) {
	}

	public record SkillCount(PeerMentorSkill skill, int meetings) {
	}

	public record MenteeOutcome(UUID menteeId, String fullName, int meetings, List<ReportRef> reports,
			int actionsDone, int actionsTotal) {
	}

	public record ReportRef(UUID meetingId, Instant scheduledAt, String outcome) {
	}
}
