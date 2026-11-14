package com.dps.teknokent.dto.responses;

import java.util.List;

import org.jspecify.annotations.Nullable;

public record MeetingReportResponse(
		String title,
		@Nullable String meetingDate,
		@Nullable String topic,
		String summary,
		List<String> topicsDiscussed,
		List<String> decisions,
		List<MeetingReportActionItem> actionItems,
		List<String> risksOrBlockers,
		List<String> nextSteps,
		String model) {
}
