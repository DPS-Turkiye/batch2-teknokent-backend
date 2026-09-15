package com.dps.teknokent.dto.requests;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateMentorAvailabilitySettingsRequest(
		@NotNull @Size(max = 21) List<@Valid CreateMentorAvailabilityRuleRequest> rules,
		@NotNull @Size(max = 30) List<@Valid MentorAvailabilityExceptionRequest> exceptions,
		@NotNull @Min(1) @Max(50) Integer menteeCapacity,
		@NotNull @Min(15) @Max(480) Integer defaultMeetingDurationMinutes,
		@NotNull @Min(0) @Max(240) Integer meetingBufferMinutes) {
}
