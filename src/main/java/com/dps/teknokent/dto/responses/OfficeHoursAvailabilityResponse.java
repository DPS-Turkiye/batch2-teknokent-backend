package com.dps.teknokent.dto.responses;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.dps.teknokent.entity.OfficeHoursException;
import com.dps.teknokent.entity.OfficeHoursRule;

/** An academic's office-hours setup, shaped like a mentor's availability settings. */
public record OfficeHoursAvailabilityResponse(
		List<Rule> rules,
		List<DateOff> exceptions,
		Integer studentCapacity,
		int defaultMeetingDurationMinutes,
		int meetingBufferMinutes) {

	public record Rule(UUID id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime, int slotMinutes) {

		public static Rule fromEntity(OfficeHoursRule rule) {
			return new Rule(rule.getId(), rule.day(), rule.getStartTime(), rule.getEndTime(), rule.getSlotMinutes());
		}
	}

	public record DateOff(UUID id, LocalDate startsOn, LocalDate endsOn, String reason) {

		public static DateOff fromEntity(OfficeHoursException exception) {
			return new DateOff(exception.getId(), exception.getStartsOn(), exception.getEndsOn(), exception.getReason());
		}
	}
}
