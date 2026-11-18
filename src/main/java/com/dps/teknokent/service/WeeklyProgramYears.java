package com.dps.teknokent.service;

import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dps.teknokent.util.ModelOutput;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fixes a printed date whose year the model misread. Reading a weekly program, the model now and then returns a week
 * that the file prints as 21.12.2026 with the year 2021, which puts the week, and the workshop held in it, on the
 * wrong date. Only a date outside the program (give or take a week) is looked at: it keeps its day and month and takes
 * the one year that puts it inside the program and in order with the weeks around it. A date that no year puts there,
 * such as an event before the program or one between weeks it does not fit, is left as it was, so a date that is wrong
 * for another reason is still flagged. Rows are taken in the order the model returns them, which is the file's; rows
 * out of order leave no year that fits, so their dates stay as they came.
 */
final class WeeklyProgramYears {

	private static final Logger log = LoggerFactory.getLogger(WeeklyProgramYears.class);

	/** How far before the program's start or after its end a printed date still counts as part of the program. */
	private static final int PROGRAM_SLACK_DAYS = 7;
	/** How far past its own week's printed days a week's end or one of its sessions may fall. */
	private static final int WEEK_SLACK_DAYS = 7;
	private static final int LAST_DAY_OF_WEEK = 6;

	private static final String SOURCE_DATE = "source_date";
	private static final String SOURCE_END_DATE = "source_end_date";
	private static final String DATE = "date";

	private WeeklyProgramYears() {
	}

	/**
	 * The model's answer with misread years fixed: each week's printed start and end, its sessions, and the special
	 * days. A week's start must fall between the printed starts of the rows around it, its end within a week after
	 * its own days, and a session within a week of them. A week that prints a start no year fits has its other dates
	 * left alone too. A special day has no neighbours, so one printed with another year whose day and month fall in
	 * the program is taken as a misread.
	 */
	static JsonNode corrected(JsonNode answer, LocalDate programStart, LocalDate programEnd) {
		if (!answer.isObject()) {
			return answer;
		}
		JsonNode copy = answer.deepCopy();
		Program program = new Program(programStart.minusDays(PROGRAM_SLACK_DAYS),
				programEnd.plusDays(PROGRAM_SLACK_DAYS));
		List<ObjectNode> rows = objects(copy.path("weeks"));
		List<@Nullable LocalDate> starts = new ArrayList<>();
		for (ObjectNode row : rows) {
			LocalDate start = ModelOutput.date(row.path(SOURCE_DATE));
			starts.add(start != null && program.contains(start) ? start : null);
		}
		for (int index = 0; index < rows.size(); index++) {
			ObjectNode row = rows.get(index);
			LocalDate start = program.fixed(row, SOURCE_DATE, previous(starts, index), next(starts, index));
			// A start fixed here bounds the next row's, so two misread weeks in a row stay in order.
			starts.set(index, start);
			if (start == null && ModelOutput.date(row.path(SOURCE_DATE)) != null) {
				continue;
			}
			LocalDate end = start == null ? program.fixed(row, SOURCE_END_DATE, null, null)
					: program.fixed(row, SOURCE_END_DATE, start,
							start.plusDays(LAST_DAY_OF_WEEK + WEEK_SLACK_DAYS));
			LocalDate weekEnd = weekEnd(start, end);
			for (ObjectNode session : objects(row.path("sessions"))) {
				program.fixed(session, DATE, start == null ? null : start.minusDays(WEEK_SLACK_DAYS),
						weekEnd == null ? null : weekEnd.plusDays(WEEK_SLACK_DAYS));
			}
		}
		for (ObjectNode day : objects(copy.path("special_days"))) {
			program.fixed(day, DATE, null, null);
		}
		if (program.corrections > 0) {
			log.info("Weekly program read: {} printed dates took the program's year", program.corrections);
		}
		return copy;
	}

	/**
	 * The date in the one year that puts it between {@code from} and {@code to}, both included; null when no year
	 * or more than one year does. The day and month never change, so 29 February has no year without one.
	 */
	static @Nullable LocalDate withYearIn(LocalDate date, LocalDate from, LocalDate to) {
		MonthDay day = MonthDay.from(date);
		LocalDate found = null;
		for (int year = from.getYear(); year <= to.getYear(); year++) {
			if (!day.isValidYear(year)) {
				continue;
			}
			LocalDate candidate = day.atYear(year);
			if (within(candidate, from, to)) {
				if (found != null) {
					return null;
				}
				found = candidate;
			}
		}
		return found;
	}

	/** The program's days, give or take its slack, and how many dates were moved into them. */
	private static final class Program {

		private final LocalDate from;
		private final LocalDate to;
		private int corrections;

		Program(LocalDate from, LocalDate to) {
			this.from = from;
			this.to = to;
		}

		boolean contains(LocalDate date) {
			return within(date, from, to);
		}

		/**
		 * The field's date once fixed: as it was when it is in the program, in the year that puts it in the program
		 * and between {@code after} and {@code before} when one does, and null when there is no date or no year fits.
		 */
		@Nullable
		LocalDate fixed(ObjectNode node, String field, @Nullable LocalDate after,
				@Nullable LocalDate before) {
			LocalDate date = ModelOutput.date(node.path(field));
			if (date == null || contains(date)) {
				return date;
			}
			LocalDate corrected = withYearIn(date, latest(from, after), earliest(to, before));
			if (corrected != null) {
				node.put(field, corrected.toString());
				corrections++;
			}
			return corrected;
		}
	}

	/** The last day of a week: its printed end when that is not before its start, otherwise six days on. */
	private static @Nullable LocalDate weekEnd(@Nullable LocalDate start, @Nullable LocalDate end) {
		if (start == null) {
			return null;
		}
		return end != null && !end.isBefore(start) ? end : start.plusDays(LAST_DAY_OF_WEEK);
	}

	private static List<ObjectNode> objects(JsonNode array) {
		List<ObjectNode> objects = new ArrayList<>();
		for (JsonNode node : array) {
			if (node instanceof ObjectNode object) {
				objects.add(object);
			}
		}
		return objects;
	}

	private static @Nullable LocalDate previous(List<@Nullable LocalDate> dates, int index) {
		for (int before = index - 1; before >= 0; before--) {
			LocalDate date = dates.get(before);
			if (date != null) {
				return date;
			}
		}
		return null;
	}

	private static @Nullable LocalDate next(List<@Nullable LocalDate> dates, int index) {
		for (int after = index + 1; after < dates.size(); after++) {
			LocalDate date = dates.get(after);
			if (date != null) {
				return date;
			}
		}
		return null;
	}

	private static LocalDate latest(LocalDate date, @Nullable LocalDate other) {
		return other != null && other.isAfter(date) ? other : date;
	}

	private static LocalDate earliest(LocalDate date, @Nullable LocalDate other) {
		return other != null && other.isBefore(date) ? other : date;
	}

	private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
		return !date.isBefore(from) && !date.isAfter(to);
	}
}
