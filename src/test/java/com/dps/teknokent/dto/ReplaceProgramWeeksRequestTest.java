package com.dps.teknokent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class ReplaceProgramWeeksRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	// A week merged from one row per session can carry twelve lines; saving it as read must not be refused.
	@Test
	void aWeekTakesTwelveLinesPerListButNotThirteen() {
		assertThat(validator.validate(week(lines(12)))).isEmpty();
		assertThat(validator.validate(week(lines(13)))).isNotEmpty();
	}

	private static ReplaceProgramWeeksRequest.WeekInput week(List<String> lines) {
		return new ReplaceProgramWeeksRequest.WeekInput(1, "Hafta 1", lines, lines, lines, false);
	}

	private static List<String> lines(int count) {
		return IntStream.rangeClosed(1, count).mapToObj(line -> "Satır " + line).toList();
	}
}
