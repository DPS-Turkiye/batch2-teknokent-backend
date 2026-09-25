package com.dps.teknokent.util;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Team codes look like {@code BIGG26-K4TX}: the program's last word, its year, and four random characters. */
public final class TeamCodes {

	// No 0/O or 1/I/L, so a code read aloud or retyped from paper comes out the same.
	private static final char[] ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int PREFIX_LETTERS = 6;

	private TeamCodes() {
	}

	/** "METU BİGG 2026" → "BIGG26", "METU Stars 2026" → "STARS26", "Hızlandırma" → "HIZLAN". */
	public static String prefix(String programName) {
		String ascii = Normalizer.normalize(programName == null ? "" : programName, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "")
				.replace('ı', 'i')
				.toUpperCase(Locale.ROOT);
		List<String> words = Arrays.stream(ascii.split("[^A-Z0-9]+")).filter(word -> !word.isBlank()).toList();
		String year = words.stream().filter(word -> word.matches("\\d{4}")).reduce((first, second) -> second)
				.map(value -> value.substring(2))
				.orElse("");
		String word = words.stream().filter(value -> !value.matches("\\d+")).reduce((first, second) -> second)
				.orElse("TAKIM");
		return word.substring(0, Math.min(PREFIX_LETTERS, word.length())) + year;
	}

	public static String next(String prefix) {
		StringBuilder code = new StringBuilder(prefix).append('-');
		for (int i = 0; i < 4; i++) {
			code.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
		}
		return code.toString();
	}
}
