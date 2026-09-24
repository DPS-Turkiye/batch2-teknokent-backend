package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A null rule leaves the stored value unchanged; dates and description are replaced as sent. */
public record UpdateProgramDetailsRequest(
		UUID actorId,
		@NotBlank @Size(max = 255) String name,
		@Size(max = 2000) String description,
		LocalDate startDate,
		LocalDate endDate,
		@Min(1) @Max(90) Integer meetingFrequencyDays,
		@Min(1) @Max(180) Integer staleAfterDays,
		Boolean meetingNoteRequired,
		Boolean autoMatchSuggestions) {
}
