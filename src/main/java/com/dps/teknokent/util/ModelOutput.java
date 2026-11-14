package com.dps.teknokent.util;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * Reads what a model returned for a schema. The model may write "null" as text, pad values with spaces, repeat a
 * line or run past the length a column holds, so each value is trimmed, capped and treated as absent when blank.
 */
public final class ModelOutput {

	private ModelOutput() {
	}

	/** The trimmed text, cut to {@code maxLength}; null when blank or the literal "null". */
	public static @Nullable String text(JsonNode node, int maxLength) {
		String trimmed = node.asString("").trim();
		if (trimmed.isEmpty() || "null".equals(trimmed)) {
			return null;
		}
		return trimmed.length() > maxLength ? trimmed.substring(0, maxLength).trim() : trimmed;
	}

	/**
	 * The distinct non-blank lines of an array, each cut to {@code maxLength}, at most {@code maxLines}. With
	 * {@code dropPlaceholders}, a line with no letter or digit (a table cell such as "–") is left out.
	 */
	public static List<String> lines(JsonNode array, int maxLines, int maxLength, boolean dropPlaceholders) {
		List<String> lines = new ArrayList<>();
		for (JsonNode item : array) {
			if (lines.size() == maxLines) {
				break;
			}
			String line = text(item, maxLength);
			if (line != null && !(dropPlaceholders && isPlaceholder(line)) && !lines.contains(line)) {
				lines.add(line);
			}
		}
		return lines;
	}

	/** An ISO date (2026-09-14); null when absent or unreadable. */
	public static @Nullable LocalDate date(JsonNode node) {
		if (!node.isString()) {
			return null;
		}
		try {
			return LocalDate.parse(node.asString().trim());
		} catch (DateTimeParseException unreadable) {
			return null;
		}
	}

	private static boolean isPlaceholder(String line) {
		return line.chars().noneMatch(Character::isLetterOrDigit);
	}
}
