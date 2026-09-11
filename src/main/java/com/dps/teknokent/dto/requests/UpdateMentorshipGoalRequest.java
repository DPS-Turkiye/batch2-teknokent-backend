package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotNull;

public record UpdateMentorshipGoalRequest(
		@NotNull Boolean done) {
}
