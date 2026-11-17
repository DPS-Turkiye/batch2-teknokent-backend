package com.dps.teknokent.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import com.dps.teknokent.dto.requests.ReplaceProgramWeeksRequest;
import com.dps.teknokent.dto.responses.ProgramWeekPlanResponse;

/**
 * Puts a weekly program's workshops on the calendar. A workshop cell that prints its own date already comes back as
 * a session on that date; one that does not is held a fixed number of days after the week's printed start, so it
 * appears on that day instead of only as an activity line. This is done here rather than in the prompt so that the
 * date does not depend on the model.
 */
final class WeeklyProgramWorkshops {

	/**
	 * Days from a week's printed start to its workshop when the workshop cell has no date: Build weeks start on
	 * Monday with the workshop on Wednesday, Explore weeks on Tuesday with the workshop on Thursday.
	 */
	static final int WORKSHOP_DAYS_AFTER_WEEK_START = 2;

	/** The prefix the prompt asks for on every workshop cell's activity line. */
	static final String WORKSHOP_PREFIX = "Workshop:";

	private static final int MAX_TITLE_LENGTH = 160;

	/**
	 * A date written in a cell: 12.11, 09.12.2026, 2026-12-09, a day and month name such as "12 Kasım", or a Turkish
	 * weekday such as "Perşembe". Accepted noise: "Python 3.11" reads as a date, so that line adds nothing.
	 */
	private static final Pattern OWN_DATE = Pattern.compile("(?<!\\d)(?:0?[1-9]|[12]\\d|3[01])[./](?:0?[1-9]|1[0-2])"
			// "2.5 saat" is a duration, not 2 May.
			+ "(?:[./]\\d{2,4})?(?!\\d)(?!\\s*(?:saat|sa|hours?|h)(?!\\p{L}))|\\d{4}-\\d{2}-\\d{2}|(?<!\\d)(?:0?[1-9]|[12]\\d|3[01])\\s+(?:ocak|şubat|subat"
			+ "|mart|nisan|mayıs|mayis|haziran|temmuz|ağustos|agustos|eylül|eylul|ekim|kasım|kasim|aralık|aralik"
			+ "|january|february|march|april|may|june|july|august|september|october|november|december)"
			// A weekday names the cell's own day too. "Pazar" (Sunday) is left out: it also means "market".
			+ "|(?<!\\p{L})(?:pazartesi|salı|sali|çarşamba|carsamba|perşembe|persembe|cuma|cumartesi)(?!\\p{L})",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private WeeklyProgramWorkshops() {
	}

	/**
	 * The week with a session for each workshop line that prints no date of its own; a dated one is already a
	 * session on its date. Nothing is added when the workshop day already has a session: either the model placed the
	 * workshop itself, or the file lists every session with its own date (Growth Circuit), and a second entry would
	 * only duplicate that day. Nor is a workshop guessed past the end the week prints for itself, or outside the
	 * program: a week start read with the wrong year (2021 for 2026) must not add a second wrong date.
	 */
	static ProgramWeekPlanResponse.Week placed(ProgramWeekPlanResponse.Week week, LocalDate printedStart,
			LocalDate programStart, LocalDate programEnd) {
		LocalDate day = printedStart.plusDays(WORKSHOP_DAYS_AFTER_WEEK_START);
		if (day.isBefore(programStart) || day.isAfter(programEnd)
				|| (week.endDate() != null && day.isAfter(week.endDate()))
				|| week.sessions().stream().anyMatch(session -> session.date().equals(day))) {
			return week;
		}
		List<ProgramWeekPlanResponse.Session> sessions = new ArrayList<>(week.sessions());
		for (String line : week.activities()) {
			if (sessions.size() >= ReplaceProgramWeeksRequest.MAX_SESSIONS) {
				break;
			}
			if (isUndatedWorkshop(line)) {
				sessions.add(new ProgramWeekPlanResponse.Session(day, null, null, null, title(line)));
			}
		}
		if (sessions.size() == week.sessions().size()) {
			return week;
		}
		sessions.sort(Comparator.comparing(ProgramWeekPlanResponse.Session::date));
		return new ProgramWeekPlanResponse.Week(week.week(), week.startDate(), week.endDate(), week.datesFromSource(),
				week.title(), week.topics(), week.activities(), week.deliverables(), week.inferred(),
				List.copyOf(sessions));
	}

	private static boolean isUndatedWorkshop(String line) {
		if (!line.regionMatches(true, 0, WORKSHOP_PREFIX, 0, WORKSHOP_PREFIX.length())) {
			return false;
		}
		String cell = line.substring(WORKSHOP_PREFIX.length());
		// "Workshop: –" is an empty cell, not a workshop.
		return cell.codePoints().anyMatch(Character::isLetterOrDigit) && !OWN_DATE.matcher(cell).find();
	}

	private static String title(String line) {
		return line.length() > MAX_TITLE_LENGTH ? line.substring(0, MAX_TITLE_LENGTH).trim() : line;
	}
}
