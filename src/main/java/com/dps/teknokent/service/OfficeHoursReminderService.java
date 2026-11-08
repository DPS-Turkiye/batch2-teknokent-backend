package com.dps.teknokent.service;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.repository.OfficeHoursMeetingRepository;

import lombok.RequiredArgsConstructor;

/**
 * Reminds both sides of an office-hours meeting by e-mail as long before it as a mentor meeting is reminded. Each
 * meeting is claimed with a conditional update first, so two servers running the scheduler never both send it.
 */
@Service
@RequiredArgsConstructor
public class OfficeHoursReminderService {

	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursMailService mailService;

	@Transactional
	public void remindUpcomingMeetings(Instant now) {
		meetingRepository
				.findByReminderSentAtIsNullAndScheduledAtBetween(now, now.plus(NotificationReminderService.MEETING_LEAD))
				.stream()
				.filter(meeting -> meetingRepository.markReminded(meeting.getId(), now) == 1)
				.forEach(mailService::meetingSoon);
	}
}
