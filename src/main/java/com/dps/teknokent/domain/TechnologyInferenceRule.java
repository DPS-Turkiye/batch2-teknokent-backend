package com.dps.teknokent.domain;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public record TechnologyInferenceRule(
		String tag,
		String type,
		String confidence,
		List<Pattern> patterns) {

	public static TechnologyInferenceRule of(
			String tag,
			String type,
			String confidence,
			String... patterns) {
		return new TechnologyInferenceRule(
				tag,
				type,
				confidence,
				Arrays.stream(patterns)
						.map(pattern -> Pattern.compile(
								pattern,
								Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
						.toList());
	}
}
