package com.dps.teknokent.service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.PersonSummaryResponse;
import com.dps.teknokent.repository.MentorAvailabilityExceptionRepository;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

/**
 * A mentor's availability as the profile summary shows it. Days off older than today (in the app's zone) and past
 * slots are left out; the mentor's reason for a day off stays private.
 */
@Service
public class MentorAvailabilitySummaryService {

	private final MentorAvailabilityRuleRepository ruleRepository;
	private final MentorAvailabilityExceptionRepository exceptionRepository;
	private final MentorAvailabilitySlotRepository slotRepository;
	private final Clock clock;

	@Autowired
	public MentorAvailabilitySummaryService(MentorAvailabilityRuleRepository ruleRepository,
			MentorAvailabilityExceptionRepository exceptionRepository, MentorAvailabilitySlotRepository slotRepository) {
		this(ruleRepository, exceptionRepository, slotRepository, Clock.systemUTC());
	}

	MentorAvailabilitySummaryService(MentorAvailabilityRuleRepository ruleRepository,
			MentorAvailabilityExceptionRepository exceptionRepository, MentorAvailabilitySlotRepository slotRepository,
			Clock clock) {
		this.ruleRepository = ruleRepository;
		this.exceptionRepository = exceptionRepository;
		this.slotRepository = slotRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public PersonSummaryResponse.Availability of(UUID mentorId) {
		Instant now = clock.instant();
		LocalDate today = LocalDate.ofInstant(now, MeetingSlotTimes.APP_ZONE);
		return new PersonSummaryResponse.Availability(
				ruleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId).stream()
						.map(rule -> new PersonSummaryResponse.WeeklyWindow(DayOfWeek.of(rule.getDayOfWeek()),
								rule.getStartTime(), rule.getEndTime()))
						.toList(),
				exceptionRepository.findByMentorIdAndEndsOnGreaterThanEqualOrderByStartsOnAsc(mentorId, today).stream()
						.map(dayOff -> new PersonSummaryResponse.DayOff(dayOff.getStartsOn(), dayOff.getEndsOn()))
						.toList(),
				slotRepository.findTop10ByMentorIdAndRuleIsNullAndStartsAtAfterOrderByStartsAtAsc(mentorId, now).stream()
						.map(slot -> new PersonSummaryResponse.ExtraSlot(slot.getStartsAt(), slot.getDurationMinutes()))
						.toList());
	}
}
