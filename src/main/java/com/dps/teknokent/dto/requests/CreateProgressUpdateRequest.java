package com.dps.teknokent.dto.requests;

import java.time.LocalDate;
import java.util.UUID;

import com.dps.teknokent.domain.ProgressStatus;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProgressUpdateRequest(
		@NotNull UUID authorId,
		@NotBlank @Size(max = 100) String category,
		@NotNull ProgressStatus status,
		@NotBlank @Size(max = 10000) String summary,
		@Size(max = 10000) String evidence,
		@NotNull LocalDate recordedAt) {
}
