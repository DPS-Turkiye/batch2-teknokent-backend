package com.dps.teknokent.dto.responses;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.entity.MentorAvailabilityException;

public record MentorAvailabilityExceptionResponse(UUID id, LocalDate startsOn, LocalDate endsOn, String reason) {
	public static MentorAvailabilityExceptionResponse fromEntity(MentorAvailabilityException exception) {
		return new MentorAvailabilityExceptionResponse(
				exception.getId(), exception.getStartsOn(), exception.getEndsOn(), exception.getReason());
	}
}
