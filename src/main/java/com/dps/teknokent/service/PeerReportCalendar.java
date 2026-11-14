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

import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.util.Texts;

/**
 * The reporting calendar of the peer-mentor reports: days, deadlines and the week's opening are in the program's zone
 * (Europe/Istanbul), whatever zone the server runs in.
 */
@Component
public class PeerReportCalendar {

	/** The weekly summary is "ready on Friday evening": it can be sent from then on. */
	static final LocalTime WEEKLY_OPENS_AT = LocalTime.of(18, 0);

	private static final int FIRST_YEAR = 2026;
	private static final int LAST_YEAR = 2100;

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

	/** Due by the next Monday, 23:59 (Arya, 14 Nov): the first instant of that Tuesday. */
	public Instant weeklyDeadline(LocalDate weekStart) {
		return startOf(weekStart.plusDays(8));
	}

	public Instant weeklyOpensAt(LocalDate weekStart) {
		return weekStart.plusDays(4).atTime(WEEKLY_OPENS_AT).atZone(zone).toInstant();
	}

	public LocalDate weekStartOf(LocalDate date) {
		return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	/** Refuses a date no report can be about, before any date arithmetic can overflow on it. */
	public static LocalDate requirePlausible(LocalDate date) {
		if (date.getYear() < FIRST_YEAR || date.getYear() > LAST_YEAR) {
			throw new InvalidRequestException(Texts.forRequest("error.peerReportDateInvalid"));
		}
		return date;
	}

	public static boolean isWorkingDay(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
	}
}
