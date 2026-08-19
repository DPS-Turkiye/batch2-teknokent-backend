package com.dps.teknokent.dto.responses;

public record DashboardSummaryResponse(
		long activeMentorships,
		long openBottlenecks,
		long highSeverityBottlenecks,
		long overdueActions,
		long upcomingMeetings) {
}
