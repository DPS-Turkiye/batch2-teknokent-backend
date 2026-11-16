package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.ProgramAssignmentSubmissionState;

/** A program Ödev in the coordinator's list, with where each team of the program stands with it. */
public record ProgramAssignmentResponse(
		UUID id,
		UUID programId,
		String title,
		@Nullable String description,
		@Nullable LocalDate dueDate,
		@Nullable UUID phaseId,
		boolean fileRequired,
		@Nullable String createdByName,
		Instant createdAt,
		int submittedCount,
		int lateCount,
		int overdueCount,
		int openCount,
		List<TeamSubmission> teams) {

	/** One team's copy; {@code documentId} opens the handed-in file through /api/documents/{id}/download-url. */
	public record TeamSubmission(
			UUID organizationId,
			String organizationName,
			UUID assignmentId,
			ProgramAssignmentSubmissionState state,
			@Nullable Instant submittedAt,
			@Nullable UUID documentId,
			@Nullable String filename) {
	}
}
