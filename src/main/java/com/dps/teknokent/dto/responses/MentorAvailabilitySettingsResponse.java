package com.dps.teknokent.dto.responses;

import java.util.List;

public record MentorAvailabilitySettingsResponse(
		List<MentorAvailabilityRuleResponse> rules,
		List<MentorAvailabilityExceptionResponse> exceptions,
		Integer menteeCapacity,
		int defaultMeetingDurationMinutes,
		int meetingBufferMinutes) {
}
