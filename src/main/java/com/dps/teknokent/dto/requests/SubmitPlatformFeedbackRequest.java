package com.dps.teknokent.dto.requests;

import java.util.UUID;

import com.dps.teknokent.domain.AppLanguage;
import com.dps.teknokent.domain.PlatformFeedbackKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitPlatformFeedbackRequest(
		UUID actorId,
		@NotNull PlatformFeedbackKind kind,
		@NotBlank @Size(max = 120) String subject,
		@NotBlank @Size(max = 2000) String message,
		@NotNull AppLanguage language) {
}
