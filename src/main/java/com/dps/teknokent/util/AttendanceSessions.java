package com.dps.teknokent.util;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.dps.teknokent.domain.AttendanceSessionKind;
import com.dps.teknokent.entity.ProgramWeek;
import com.dps.teknokent.entity.ProgramWeekSession;

/**
 * The two sessions of a program week that attendance is taken for, read from the program's current plan:
 * <ul>
 * <li>the <b>lesson</b> (Eğitim / Konuşmacı) is on the week's first day, the printed start where the plan has one,
 * else the program calendar's; it takes the times of a session on that day when there is one (Growth Circuit's
 * Monday 18:00–20:00), else it lasts the whole day;</li>
 * <li>the <b>workshop</b> is a later session of the week whose title says so (Workshop, Atölye), else the week's
 * last session after the lesson's day (the undated workshop the plan reading places on start + 2, Growth Circuit's
 * Friday atölye). A week with no session after its first day has no workshop.</li>
 * </ul>
 * A session in between (Growth Circuit's Wednesday) takes no attendance. Nothing here is stored: a plan read again
 * moves the sessions with it.
 */
public final class AttendanceSessions {

	/** How early a session's check-in opens before its start time. */
	public static final Duration OPENS_BEFORE = Duration.ofMinutes(30);

	/** How long a session's check-in stays open after its end time. */
	public static final Duration CLOSES_AFTER = Duration.ofHours(1);

	private static final Pattern WORKSHOP_TITLE = Pattern.compile("workshop|atölye|atolye",
			Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	private static final Comparator<ProgramWeekSession> BY_DAY_AND_TIME = Comparator
			.comparing(ProgramWeekSession::getDate)
			.thenComparing(ProgramWeekSession::getStartTime, Comparator.nullsFirst(Comparator.naturalOrder()));

	/** A session that takes attendance. Without a start time it lasts its whole day. */
	public record Session(int week, AttendanceSessionKind kind, LocalDate date, @Nullable LocalTime startTime,
			@Nullable LocalTime endTime, @Nullable String title) {

		/** When check-in opens: {@link #OPENS_BEFORE} before the start time, or the start of the day without one. */
		public Instant opensAt() {
			LocalTime start = startTime;
			return start == null ? date.atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant()
					: date.atTime(start).atZone(MeetingSlotTimes.APP_ZONE).toInstant().minus(OPENS_BEFORE);
		}

		/**
		 * When check-in closes: {@link #CLOSES_AFTER} after the end time, or the end of the day when the session
		 * prints no end (or an end before its start).
		 */
		public Instant closesAt() {
			LocalTime start = startTime;
			LocalTime end = endTime;
			if (end != null && (start == null || !end.isBefore(start))) {
				return date.atTime(end).atZone(MeetingSlotTimes.APP_ZONE).toInstant().plus(CLOSES_AFTER);
			}
			return date.plusDays(1).atStartOfDay(MeetingSlotTimes.APP_ZONE).toInstant();
		}

		public boolean isOpenAt(Instant now) {
			return !now.isBefore(opensAt()) && now.isBefore(closesAt());
		}
	}

	private AttendanceSessions() {
	}

	/**
	 * Week {@code week}'s lesson, then its workshop when it has one; empty when the week has no dates (a program
	 * without a start date and a plan that prints none). {@code planned} is the saved week, null when the plan has
	 * no row for it.
	 */
	public static List<Session> of(ProgramSchedule schedule, @Nullable ProgramWeek planned, int week) {
		ProgramSchedule.Dates dates = schedule.datesOf(week);
		if (dates == null) {
			return List.of();
		}
		LocalDate lessonDay = dates.start();
		List<ProgramWeekSession> sessions = planned == null ? List.of()
				: planned.getSessions().stream().sorted(BY_DAY_AND_TIME).toList();
		String weekTitle = planned == null ? null : TextUtils.normalizeOptional(planned.getTitle());
		List<Session> found = new ArrayList<>(2);
		found.add(sessions.stream()
				.filter(session -> session.getDate().equals(lessonDay))
				.findFirst()
				.map(lesson -> sessionOf(week, AttendanceSessionKind.LESSON, lesson, weekTitle))
				.orElseGet(() -> new Session(week, AttendanceSessionKind.LESSON, lessonDay, null, null, weekTitle)));
		List<ProgramWeekSession> later = sessions.stream()
				.filter(session -> session.getDate().isAfter(lessonDay))
				.toList();
		later.stream()
				.filter(AttendanceSessions::isWorkshop)
				.findFirst()
				.or(() -> later.isEmpty() ? Optional.empty() : Optional.of(later.getLast()))
				.ifPresent(workshop -> found.add(sessionOf(week, AttendanceSessionKind.WORKSHOP, workshop, null)));
		return List.copyOf(found);
	}

	/** Week {@code week}'s session of {@code kind}; null when the week has none (always for WEEK). */
	public static @Nullable Session find(ProgramSchedule schedule, @Nullable ProgramWeek planned, int week,
			AttendanceSessionKind kind) {
		return of(schedule, planned, week).stream()
				.filter(session -> session.kind() == kind)
				.findFirst()
				.orElse(null);
	}

	private static boolean isWorkshop(ProgramWeekSession session) {
		String title = session.getTitle();
		return title != null && WORKSHOP_TITLE.matcher(title).find();
	}

	private static Session sessionOf(int week, AttendanceSessionKind kind, ProgramWeekSession session,
			@Nullable String fallbackTitle) {
		String title = TextUtils.normalizeOptional(session.getTitle());
		return new Session(week, kind, session.getDate(), session.getStartTime(), session.getEndTime(),
				title == null ? fallbackTitle : title);
	}
}
