package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.TrainingState;

/** The signed-in student's training; never the questions' correct options. */
public record StudentTrainingResponse(
		TrainingState state,
		@Nullable UUID programId,
		@Nullable String programName,
		@Nullable Integer version,
		@Nullable String videoId,
		boolean videoWatched,
		int attemptsLeft,
		@Nullable Instant nextAttemptAt,
		@Nullable Integer lastScore,
		int passMark,
		int questionCount) {
}
