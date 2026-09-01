package com.dps.teknokent.dto.requests;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record AddProgramMentorRequest(
		@NotNull UUID mentorId,
		UUID actorId) {
}
