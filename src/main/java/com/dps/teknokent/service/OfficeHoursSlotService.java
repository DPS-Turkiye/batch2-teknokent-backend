package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.OfficeHoursException;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRule;
import com.dps.teknokent.repository.OfficeHoursExceptionRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRuleRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/**
 * The times a student can pick from an academic's office hours, made the way a mentor's availability turns into
 * slots: each weekly range split into its slot length over the next eight weeks, leaving out dates away, times
 * already booked, and anything sooner than {@link MeetingSlotTimes#MIN_BOOKING_LEAD}.
 */
@Service
public class OfficeHoursSlotService {

	static final int HORIZON_WEEKS = 8;

	public record Slot(Instant startsAt, int durationMinutes) {
	}

	private final OfficeHoursRuleRepository ruleRepository;
	private final OfficeHoursExceptionRepository exceptionRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final Clock clock;

	@Autowired
	public OfficeHoursSlotService(OfficeHoursRuleRepository ruleRepository,
			OfficeHoursExceptionRepository exceptionRepository, OfficeHoursMeetingRepository meetingRepository) {
		this(ruleRepository, exceptionRepository, meetingRepository, Clock.systemUTC());
	}

	OfficeHoursSlotService(OfficeHoursRuleRepository ruleRepository, OfficeHoursExceptionRepository exceptionRepository,
			OfficeHoursMeetingRepository meetingRepository, Clock clock) {
		this.ruleRepository = ruleRepository;
		this.exceptionRepository = exceptionRepository;
		this.meetingRepository = meetingRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Slot> openSlots(UUID instructorId) {
		Instant now = clock.instant();
		Instant earliest = now.plus(MeetingSlotTimes.MIN_BOOKING_LEAD);
		ZonedDateTime start = now.atZone(MeetingSlotTimes.APP_ZONE);
		ZonedDateTime horizon = start.plusWeeks(HORIZON_WEEKS);
		List<OfficeHoursException> away = exceptionRepository.findByInstructorIdOrderByStartsOnAsc(instructorId);
		Set<Instant> booked = new HashSet<>();
		for (OfficeHoursMeeting meeting : meetingRepository.findByInstructorIdAndScheduledAtAfter(instructorId, now)) {
			booked.add(meeting.getScheduledAt());
		}

		List<Slot> slots = new ArrayList<>();
		for (OfficeHoursRule rule : ruleRepository.findByInstructorIdOrderByDayOfWeekAscStartTimeAsc(instructorId)) {
			ZonedDateTime day = start.toLocalDate().atStartOfDay(MeetingSlotTimes.APP_ZONE)
					.with(TemporalAdjusters.nextOrSame(rule.day()));
			for (; day.isBefore(horizon); day = day.plusWeeks(1)) {
				if (isAway(away, day.toLocalDate())) {
					continue;
				}
				ZonedDateTime end = day.with(rule.getEndTime());
				for (ZonedDateTime at = day.with(rule.getStartTime());
						!at.plusMinutes(rule.getSlotMinutes()).isAfter(end); at = at.plusMinutes(rule.getSlotMinutes())) {
					Instant startsAt = at.toInstant();
					if (!startsAt.isBefore(earliest) && !booked.contains(startsAt)) {
						slots.add(new Slot(startsAt, rule.getSlotMinutes()));
					}
				}
			}
		}
		slots.sort(Comparator.comparing(Slot::startsAt));
		return slots;
	}

	/** The open slot starting at this moment, if there is one; a time typed by hand has none. */
	@Transactional(readOnly = true)
	public Optional<Slot> openSlotAt(UUID instructorId, Instant startsAt) {
		return openSlots(instructorId).stream().filter(slot -> slot.startsAt().equals(startsAt)).findFirst();
	}

	private static boolean isAway(List<OfficeHoursException> away, LocalDate day) {
		return away.stream().anyMatch(range -> !day.isBefore(range.getStartsOn()) && !day.isAfter(range.getEndsOn()));
	}
}
