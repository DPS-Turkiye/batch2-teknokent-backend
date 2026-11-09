package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.OfficeHoursException;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRule;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.repository.OfficeHoursExceptionRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRuleRepository;
import com.dps.teknokent.repository.OfficeHoursSettingsRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/**
 * The times a student can pick from an academic's office hours, made the way a mentor's availability turns into
 * slots: each weekly range split into its slot length over the next eight weeks, leaving out dates away, anything
 * sooner than {@link MeetingSlotTimes#MIN_BOOKING_LEAD}, and any slot that overlaps a meeting already on the
 * calendar or comes closer to it than the academic's buffer.
 */
@Service
public class OfficeHoursSlotService {

	static final int HORIZON_WEEKS = 8;
	/** Meetings never run longer than the 480-minute slot limit, so this is far enough back to catch overlaps. */
	private static final Duration LOOK_BACK = Duration.ofHours(9);

	public record Slot(Instant startsAt, int durationMinutes) {
	}

	private final OfficeHoursRuleRepository ruleRepository;
	private final OfficeHoursExceptionRepository exceptionRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursSettingsRepository settingsRepository;
	private final Clock clock;

	@Autowired
	public OfficeHoursSlotService(OfficeHoursRuleRepository ruleRepository,
			OfficeHoursExceptionRepository exceptionRepository, OfficeHoursMeetingRepository meetingRepository,
			OfficeHoursSettingsRepository settingsRepository) {
		this(ruleRepository, exceptionRepository, meetingRepository, settingsRepository, Clock.systemUTC());
	}

	OfficeHoursSlotService(OfficeHoursRuleRepository ruleRepository, OfficeHoursExceptionRepository exceptionRepository,
			OfficeHoursMeetingRepository meetingRepository, OfficeHoursSettingsRepository settingsRepository,
			Clock clock) {
		this.ruleRepository = ruleRepository;
		this.exceptionRepository = exceptionRepository;
		this.meetingRepository = meetingRepository;
		this.settingsRepository = settingsRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Slot> openSlots(UUID instructorId) {
		Instant now = clock.instant();
		Instant earliest = now.plus(MeetingSlotTimes.MIN_BOOKING_LEAD);
		ZonedDateTime start = now.atZone(MeetingSlotTimes.APP_ZONE);
		ZonedDateTime horizon = start.plusWeeks(HORIZON_WEEKS);
		List<OfficeHoursException> away = exceptionRepository.findByInstructorIdOrderByStartsOnAsc(instructorId);
		List<OfficeHoursMeeting> meetings = upcomingMeetings(instructorId, now);
		Duration buffer = buffer(instructorId);

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
					Duration length = Duration.ofMinutes(rule.getSlotMinutes());
					if (!startsAt.isBefore(earliest) && !overlaps(meetings, startsAt, length, buffer)) {
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

	/**
	 * True when a meeting from {@code startsAt} lasting {@code minutes} would overlap one already on the academic's
	 * calendar, or come closer to it than their buffer.
	 */
	@Transactional(readOnly = true)
	public boolean clashesWithCalendar(UUID instructorId, Instant startsAt, int minutes) {
		return overlaps(upcomingMeetings(instructorId, clock.instant()), startsAt, Duration.ofMinutes(minutes),
				buffer(instructorId));
	}

	/** The academic's default meeting length, used for a time typed by hand rather than picked from a slot. */
	@Transactional(readOnly = true)
	public int defaultMeetingMinutes(UUID instructorId) {
		return settings(instructorId).getDefaultMeetingDurationMinutes();
	}

	static boolean overlaps(List<OfficeHoursMeeting> meetings, Instant startsAt, Duration length, Duration buffer) {
		Instant end = startsAt.plus(length);
		return meetings.stream().anyMatch(meeting -> {
			Instant blockedFrom = meeting.getScheduledAt().minus(buffer);
			Instant blockedUntil = meeting.getScheduledAt().plus(Duration.ofMinutes(meeting.getDurationMinutes()))
					.plus(buffer);
			return startsAt.isBefore(blockedUntil) && blockedFrom.isBefore(end);
		});
	}

	private List<OfficeHoursMeeting> upcomingMeetings(UUID instructorId, Instant now) {
		return meetingRepository.findByInstructorIdAndScheduledAtAfter(instructorId, now.minus(LOOK_BACK));
	}

	private Duration buffer(UUID instructorId) {
		return Duration.ofMinutes(settings(instructorId).getMeetingBufferMinutes());
	}

	private OfficeHoursSettings settings(UUID instructorId) {
		return settingsRepository.findById(instructorId).orElseGet(OfficeHoursSettings::new);
	}

	private static boolean isAway(List<OfficeHoursException> away, LocalDate day) {
		return away.stream().anyMatch(range -> !day.isBefore(range.getStartsOn()) && !day.isAfter(range.getEndsOn()));
	}
}
