package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipStatus;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateMentorshipRequest(
		@NotNull UUID mentorId,
		@NotNull UUID organizationId,
		@NotNull MentorshipStatus status,
		@NotNull LocalDate startDate,
		LocalDate endDate,
		@Size(max = 5000) String goals) {
}
