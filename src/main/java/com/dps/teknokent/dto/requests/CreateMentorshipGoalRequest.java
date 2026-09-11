package com.dps.teknokent.dto.requests;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMentorshipGoalRequest(
		@NotBlank @Size(max = 500) String title) {
}
