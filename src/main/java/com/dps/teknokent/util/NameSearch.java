package com.dps.teknokent.util;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Name search that ignores case and Turkish letters, so "cagri" finds "Çağrı" and "IŞIK" finds "Işık": the text is
 * lowercased, dotless "ı" becomes "i" and the accents (ç, ğ, ö, ş, ü, â, and the dot of "İ") are dropped. Every word
 * of the query has to appear somewhere in the name. The frontend's {@code matchesPersonName} folds the same way.
 */
public final class NameSearch {

	/** Any space, including the no-break and other Unicode spaces JavaScript's {@code \s} also covers. */
	private static final Pattern SPACES = Pattern.compile("[\\s\\p{Z}\\uFEFF]+");
	private static final Pattern MARKS = Pattern.compile("\\p{M}+");

	private NameSearch() {
	}

	public static String fold(@Nullable String value) {
		if (value == null) {
			return "";
		}
		String lower = value.toLowerCase(Locale.ROOT).replace('ı', 'i');
		String bare = MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
		return SPACES.matcher(bare).replaceAll(" ").trim();
	}

	/** The query's words, folded; empty for a blank query. */
	public static List<String> words(@Nullable String query) {
		String folded = fold(query);
		return folded.isEmpty() ? List.of() : Arrays.asList(folded.split(" "));
	}

	public static boolean matches(@Nullable String name, List<String> words) {
		String folded = fold(name);
		return !words.isEmpty() && words.stream().allMatch(folded::contains);
	}
}
