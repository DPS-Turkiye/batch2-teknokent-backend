package com.dps.teknokent.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The reporting calendar of the peer-mentor reports: days, deadlines and the week's opening are in the program's zone
 * (Europe/Istanbul), whatever zone the server runs in.
 */
@Component
public class PeerReportCalendar {

	/** The weekly summary is "ready on Friday evening": it can be sent from then on. */
	static final LocalTime WEEKLY_OPENS_AT = LocalTime.of(18, 0);

	private final ZoneId zone;
	private final Clock clock;

	@Autowired
	public PeerReportCalendar(@Value("${peer.reports.zone:Europe/Istanbul}") String zone) {
		this(ZoneId.of(zone), Clock.systemUTC());
	}

	PeerReportCalendar(ZoneId zone, Clock clock) {
		this.zone = zone;
		this.clock = clock;
	}

	public Instant now() {
		return clock.instant();
	}

	public LocalDate today() {
		return LocalDate.ofInstant(now(), zone);
	}

	public LocalDate dateOf(Instant instant) {
		return LocalDate.ofInstant(instant, zone);
	}

	public Instant startOf(LocalDate date) {
		return date.atStartOfDay(zone).toInstant();
	}

	/** "23:59'a kadar": the first instant of the next day. */
	public Instant dailyDeadline(LocalDate date) {
		return startOf(date.plusDays(1));
	}

	/** The Sunday-night close of the week starting {@code weekStart}. */
	public Instant weeklyDeadline(LocalDate weekStart) {
		return startOf(weekStart.plusDays(7));
	}

	public Instant weeklyOpensAt(LocalDate weekStart) {
		return weekStart.plusDays(4).atTime(WEEKLY_OPENS_AT).atZone(zone).toInstant();
	}

	public LocalDate weekStartOf(LocalDate date) {
		return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	public static boolean isWorkingDay(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
	}
}
