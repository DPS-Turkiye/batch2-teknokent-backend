package com.dps.teknokent.dto.responses;

import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The training as its editors (the program's coordinators and admins) see it, correct options included.
 * {@code placeholder}: nothing is saved yet and these are the clearly marked example video and questions.
 */
public record TrainingModuleResponse(
		boolean published,
		@Nullable Integer version,
		String videoId,
		@Nullable Instant updatedAt,
		boolean placeholder,
		List<Question> questions) {

	public record Question(String text, List<String> options, int correctOption) {
	}
}
