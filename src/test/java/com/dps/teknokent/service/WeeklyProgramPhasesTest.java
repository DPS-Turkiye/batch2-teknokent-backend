package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.dps.teknokent.service.WeeklyProgramPhases.Range;
import com.dps.teknokent.service.WeeklyProgramPhases.Result;

class WeeklyProgramPhasesTest {

	@Test
	void theAiRangesAreUsedWhenTheyCoverEveryWeekInOrder() {
		List<Range> proposed = List.of(
				new Range("Fikir", null, 4, 6),
				new Range("Problem", "Problem tanımı", 1, 3));

		Result result = WeeklyProgramPhases.group(proposed, weeks("Problem", "Problem", "Problem", "Fikir",
				"Fikir", "Fikir"));

		assertThat(result.phases()).containsExactly(
				new Range("Problem", "Problem tanımı", 1, 3),
				new Range("Fikir", null, 4, 6));
		assertThat(result.rebuilt()).isFalse();
		assertThat(result.merged()).isFalse();
	}

	@Test
	void overlappingRangesAreRebuiltFromEachWeeksPhaseKeepingTheOutputs() {
		List<Range> proposed = List.of(
				new Range("Problem", "Problem tanımı", 1, 3),
				new Range("Fikir", "Fikir listesi", 3, 4));

		Result result = WeeklyProgramPhases.group(proposed, weeks("Problem", "Problem", "Fikir", "fikir "));

		assertThat(result.phases()).containsExactly(
				new Range("Problem", "Problem tanımı", 1, 2),
				new Range("Fikir", "Fikir listesi", 3, 4));
		assertThat(result.rebuilt()).isTrue();
	}

	@Test
	void aRangeThatMissesAReadWeekIsRebuilt() {
		Result result = WeeklyProgramPhases.group(List.of(new Range("Problem", null, 1, 2)),
				weeks("Problem", "Problem", "Pazar"));

		assertThat(result.phases()).extracting(Range::name).containsExactly("Problem", "Pazar");
		assertThat(result.rebuilt()).isTrue();
	}

	@Test
	void aWeekWithoutAPhaseStaysInThePhaseBeforeIt() {
		Result result = WeeklyProgramPhases.group(List.of(), weeks(null, "Problem", null, "Pazar", null));

		assertThat(result.phases()).containsExactly(
				new Range("Problem", null, 1, 3),
				new Range("Pazar", null, 4, 5));
	}

	@Test
	void weeksWithoutAnyPhaseGiveNoPhases() {
		Result result = WeeklyProgramPhases.group(List.of(), weeks(null, null));

		assertThat(result.phases()).isEmpty();
		assertThat(result.rebuilt()).isFalse();
	}

	@Test
	void phasesPastTheLimitAreMergedIntoTheLastOne() {
		String[] names = IntStream.rangeClosed(1, 14).mapToObj(n -> "Faz " + n).toArray(String[]::new);

		Result result = WeeklyProgramPhases.group(List.of(), weeks(names));

		assertThat(result.phases()).hasSize(WeeklyProgramPhases.MAX_PHASES);
		assertThat(result.phases().getLast()).isEqualTo(new Range("Faz 12", null, 12, 14));
		assertThat(result.merged()).isTrue();
	}

	@Test
	void aMissingWeekLeavesAGapBetweenPhases() {
		SortedMap<Integer, String> read = new TreeMap<>();
		read.put(1, "Problem");
		read.put(2, "Problem");
		read.put(4, "Pazar");

		Result result = WeeklyProgramPhases.group(List.of(), read);

		assertThat(result.phases()).containsExactly(new Range("Problem", null, 1, 2), new Range("Pazar", null, 4, 4));
	}

	private static SortedMap<Integer, String> weeks(String... phases) {
		SortedMap<Integer, String> weeks = new TreeMap<>();
		for (int i = 0; i < phases.length; i++) {
			weeks.put(i + 1, phases[i]);
		}
		return weeks;
	}
}
