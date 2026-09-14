package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import com.dps.teknokent.domain.MentorshipRequestStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DecideMentorshipRequest(
		@NotNull UUID mentorId,
		@NotNull MentorshipRequestStatus status,
		@NotBlank @Size(max = 5000) String message,
		List<@Size(max = 500) String> requestedDocuments) {
}
