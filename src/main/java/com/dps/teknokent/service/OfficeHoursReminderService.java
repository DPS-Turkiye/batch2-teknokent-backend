package com.dps.teknokent.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.service.OfficeHoursMailService.Delivery;
import com.dps.teknokent.service.OfficeHoursMailService.Side;

import lombok.RequiredArgsConstructor;

/**
 * Reminds each side of an office-hours meeting by e-mail 24 hours before it (FR-OH-17). Each side is claimed with a
 * conditional update first, so two servers running the scheduler never both send it; a send that fails gives the
 * claim back, so the next run tries again until the meeting starts, without repeating the other side's reminder.
 */
@Service
@RequiredArgsConstructor
public class OfficeHoursReminderService {

	/** SRS FR-OH-17: a day ahead, unlike a mentor meeting's 30 minutes. */
	static final Duration REMINDER_LEAD = Duration.ofHours(24);

	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursMailService mailService;

	public void remindUpcomingMeetings(Instant now) {
		for (OfficeHoursMeeting meeting : meetingRepository.findReminderDue(now, now.plus(REMINDER_LEAD))) {
			if (meeting.getStudentRemindedAt() == null) {
				remind(meeting, Side.STUDENT, now);
			}
			if (meeting.getInstructorRemindedAt() == null) {
				remind(meeting, Side.INSTRUCTOR, now);
			}
		}
	}

	private void remind(OfficeHoursMeeting meeting, Side side, Instant now) {
		if (claim(meeting, side, now) != 1) {
			return;
		}
		if (mailService.meetingSoon(meeting, side) == Delivery.FAILED) {
			release(meeting, side);
		}
	}

	private int claim(OfficeHoursMeeting meeting, Side side, Instant now) {
		return switch (side) {
			case STUDENT -> meetingRepository.claimStudentReminder(meeting.getId(), now);
			case INSTRUCTOR -> meetingRepository.claimInstructorReminder(meeting.getId(), now);
		};
	}

	private void release(OfficeHoursMeeting meeting, Side side) {
		switch (side) {
			case STUDENT -> meetingRepository.releaseStudentReminder(meeting.getId());
			case INSTRUCTOR -> meetingRepository.releaseInstructorReminder(meeting.getId());
		}
	}
}
