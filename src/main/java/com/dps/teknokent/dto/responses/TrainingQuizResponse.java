package com.dps.teknokent.dto.responses;

import java.util.List;
import java.util.UUID;

/** The quiz in a fresh order for one attempt; an option is answered by its {@code index}, not its place. */
public record TrainingQuizResponse(int version, List<Question> questions) {

	public record Question(UUID id, String text, List<Option> options) {
	}

	public record Option(int index, String text) {
	}
}
