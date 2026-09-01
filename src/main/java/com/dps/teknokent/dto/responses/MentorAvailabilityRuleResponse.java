package com.dps.teknokent.dto.responses;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

import com.dps.teknokent.entity.MentorAvailabilityRule;

public record MentorAvailabilityRuleResponse(
		UUID id,
		DayOfWeek dayOfWeek,
		LocalTime startTime,
		LocalTime endTime,
		int slotMinutes) {

	public static MentorAvailabilityRuleResponse fromEntity(MentorAvailabilityRule rule) {
		return new MentorAvailabilityRuleResponse(
				rule.getId(),
				rule.day(),
				rule.getStartTime(),
				rule.getEndTime(),
				rule.getSlotMinutes());
	}
}
