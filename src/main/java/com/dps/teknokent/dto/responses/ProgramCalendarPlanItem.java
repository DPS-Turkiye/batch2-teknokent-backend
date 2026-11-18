package com.dps.teknokent.dto.responses;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * What a calendar shows of an entry that comes from a program's weekly plan: a session of a week ({@code week} and
 * {@code weekTitle} set) or a special day (both null). {@code speaker} is the week's "Konuşmacı" line, given on the
 * sessions held on the week's first day (its lesson). {@code allDay} when the plan prints no time.
 */
public record ProgramCalendarPlanItem(
		@Nullable Integer week,
		@Nullable String weekTitle,
		@Nullable String place,
		@Nullable String speaker,
		List<String> details,
		boolean allDay) {
}
