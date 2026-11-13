package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.entity.OfficeHoursException;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursRule;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.repository.OfficeHoursExceptionRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
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
	private static final Set<OfficeHoursRequestStatus> HOLDING = Set.of(OfficeHoursRequestStatus.PENDING,
			OfficeHoursRequestStatus.MORE_INFO_REQUESTED, OfficeHoursRequestStatus.COUNTER_PROPOSED);
	private static final Duration LOOK_BACK = Duration.ofHours(9);

	public record Slot(Instant startsAt, int durationMinutes) {
	}

	private final OfficeHoursRuleRepository ruleRepository;
	private final OfficeHoursExceptionRepository exceptionRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursSettingsRepository settingsRepository;
	private final OfficeHoursRequestRepository requestRepository;
	private final Clock clock;

	@Autowired
	public OfficeHoursSlotService(OfficeHoursRuleRepository ruleRepository,
			OfficeHoursExceptionRepository exceptionRepository, OfficeHoursMeetingRepository meetingRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursRequestRepository requestRepository) {
		this(ruleRepository, exceptionRepository, meetingRepository, settingsRepository, requestRepository,
				Clock.systemUTC());
	}

	OfficeHoursSlotService(OfficeHoursRuleRepository ruleRepository, OfficeHoursExceptionRepository exceptionRepository,
			OfficeHoursMeetingRepository meetingRepository, OfficeHoursSettingsRepository settingsRepository,
			OfficeHoursRequestRepository requestRepository, Clock clock) {
		this.ruleRepository = ruleRepository;
		this.exceptionRepository = exceptionRepository;
		this.meetingRepository = meetingRepository;
		this.settingsRepository = settingsRepository;
		this.requestRepository = requestRepository;
		this.clock = clock;
	}

	/** The slots a student can still ask for: the academic's hours, minus booked meetings and held times. */
	@Transactional(readOnly = true)
	public List<Slot> openSlots(UUID instructorId) {
		Set<Instant> held = heldTimes(instructorId, null);
		return calendarSlots(instructorId).stream().filter(slot -> !held.contains(slot.startsAt())).toList();
	}

	/**
	 * Times open requests hold, so no one else is offered them (FR-OH-16): what a request waiting on the academic
	 * asks for, and what the academic offered on one waiting on the student. {@code exceptRequestId}'s own times
	 * don't count.
	 */
	@Transactional(readOnly = true)
	public Set<Instant> heldTimes(UUID instructorId, UUID exceptRequestId) {
		return requestRepository.findByInstructorIdAndStatusIn(instructorId, HOLDING).stream()
				.filter(request -> !request.getId().equals(exceptRequestId))
				.flatMap(OfficeHoursSlotService::timesHeldBy)
				.collect(Collectors.toSet());
	}

	private static Stream<Instant> timesHeldBy(OfficeHoursRequest request) {
		String joined = request.getStatus() == OfficeHoursRequestStatus.COUNTER_PROPOSED
				? request.getProposedSlots()
				: request.getPreferredSlots();
		if (joined == null || joined.isBlank()) {
			return Stream.empty();
		}
		return Arrays.stream(joined.split(",")).map(String::trim).map(MeetingSlotTimes::parseMachineSlot)
				.filter(Objects::nonNull);
	}

	/** The academic's hours as slots, minus booked meetings; times other requests hold are still in. */
	public List<Slot> calendarSlots(UUID instructorId) {
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

	/**
	 * The academic's slot starting at this moment, if there is one; a time typed by hand has none. Held times still
	 * count here: a request's own time has to keep its slot's length when it is accepted.
	 */
	@Transactional(readOnly = true)
	public Optional<Slot> openSlotAt(UUID instructorId, Instant startsAt) {
		return calendarSlots(instructorId).stream().filter(slot -> slot.startsAt().equals(startsAt)).findFirst();
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
