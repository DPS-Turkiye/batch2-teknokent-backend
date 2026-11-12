package com.dps.teknokent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Turns the weeks read from a weekly program into phases: consecutive week ranges, in order. The AI's own ranges are
 * used when they fit the weeks; otherwise consecutive weeks with the same phase name are grouped. Pure, so the
 * grouping rules are tested without the AI.
 */
final class WeeklyProgramPhases {

	static final int MAX_PHASES = 12;

	private WeeklyProgramPhases() {
	}

	record Range(String name, String expectedOutput, int firstWeek, int lastWeek) {
	}

	/** {@code rebuilt}: the AI's ranges were not used. {@code merged}: phases past the limit were folded in. */
	record Result(List<Range> phases, boolean rebuilt, boolean merged) {
	}

	/**
	 * @param proposed the AI's phases, as returned
	 * @param weekPhases each read week's number and the phase name the AI gave it (null when it gave none)
	 */
	static Result group(List<Range> proposed, SortedMap<Integer, String> weekPhases) {
		if (weekPhases.isEmpty()) {
			return new Result(List.of(), false, false);
		}
		List<Range> phases = fits(proposed, weekPhases) ? sorted(proposed) : null;
		boolean rebuilt = phases == null;
		if (rebuilt) {
			phases = byWeekName(weekPhases, outputsByName(proposed));
		}
		if (phases.size() <= MAX_PHASES) {
			return new Result(phases, rebuilt && !phases.isEmpty(), false);
		}
		List<Range> capped = new ArrayList<>(phases.subList(0, MAX_PHASES - 1));
		Range last = phases.get(MAX_PHASES - 1);
		capped.add(new Range(last.name(), last.expectedOutput(), last.firstWeek(), phases.getLast().lastWeek()));
		return new Result(capped, rebuilt, true);
	}

	/** Ordered, non-overlapping ranges that cover every read week exactly once. */
	private static boolean fits(List<Range> proposed, SortedMap<Integer, String> weekPhases) {
		if (proposed.isEmpty()) {
			return false;
		}
		List<Range> ranges = sorted(proposed);
		int previousLast = 0;
		for (Range range : ranges) {
			if (range.firstWeek() < 1 || range.lastWeek() < range.firstWeek() || range.firstWeek() <= previousLast) {
				return false;
			}
			previousLast = range.lastWeek();
		}
		return weekPhases.keySet().stream()
				.allMatch(week -> ranges.stream().anyMatch(r -> r.firstWeek() <= week && week <= r.lastWeek()));
	}

	/**
	 * Consecutive weeks with the same phase name form one phase. A week without a name stays in the phase before
	 * it (the first weeks join the first named phase); with no names at all there are no phases.
	 */
	private static List<Range> byWeekName(SortedMap<Integer, String> weekPhases, Map<String, String> outputs) {
		String carried = weekPhases.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
		if (carried == null) {
			return List.of();
		}
		List<Range> phases = new ArrayList<>();
		String name = null;
		int first = 0;
		int last = 0;
		for (Map.Entry<Integer, String> week : weekPhases.entrySet()) {
			String weekName = week.getValue() == null ? carried : week.getValue();
			carried = weekName;
			if (name != null && key(name).equals(key(weekName))) {
				last = week.getKey();
				continue;
			}
			if (name != null) {
				phases.add(new Range(name, outputs.get(key(name)), first, last));
			}
			name = weekName;
			first = week.getKey();
			last = week.getKey();
		}
		phases.add(new Range(name, outputs.get(key(name)), first, last));
		return phases;
	}

	private static Map<String, String> outputsByName(List<Range> proposed) {
		Map<String, String> outputs = new HashMap<>();
		for (Range range : proposed) {
			if (range.expectedOutput() != null) {
				outputs.putIfAbsent(key(range.name()), range.expectedOutput());
			}
		}
		return outputs;
	}

	private static List<Range> sorted(List<Range> ranges) {
		return ranges.stream().sorted(Comparator.comparingInt(Range::firstWeek)).toList();
	}

	private static String key(String name) {
		return name.trim().toLowerCase(Locale.ROOT);
	}
}
