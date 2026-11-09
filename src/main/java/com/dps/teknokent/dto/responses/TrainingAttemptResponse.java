package com.dps.teknokent.dto.responses;

/** The outcome of one submission: the score only, never which answers were right (D2c). */
public record TrainingAttemptResponse(int score, boolean passed, StudentTrainingResponse training) {
}
