package com.dps.teknokent.dto.requests;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Save and publish the program's training: a YouTube link and exactly ten questions with four options each. */
public record SaveTrainingRequest(
		UUID actorId,
		@NotBlank @Size(max = 300) String videoUrl,
		@NotNull @Size(min = 10, max = 10) List<@Valid @NotNull Question> questions) {

	public record Question(
			@NotBlank @Size(max = 500) String text,
			@NotNull @Size(min = 4, max = 4) List<@NotBlank @Size(max = 200) String> options,
			@Min(0) @Max(3) int correctOption) {
	}
}
