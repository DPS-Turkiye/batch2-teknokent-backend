package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.UUID;

import com.dps.teknokent.domain.TrainingState;

/** The signed-in student's training; never the questions' correct options. */
public record StudentTrainingResponse(
		TrainingState state,
		UUID programId,
		String programName,
		Integer version,
		String videoId,
		boolean videoWatched,
		int attemptsLeft,
		Instant nextAttemptAt,
		Integer lastScore,
		int passMark,
		int questionCount) {
}
