package com.dps.teknokent.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.service.OfficeHoursMailService.Delivery;
import com.dps.teknokent.service.OfficeHoursMailService.Side;

@ExtendWith(MockitoExtension.class)
class OfficeHoursReminderServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

	@Mock
	private OfficeHoursMeetingRepository meetingRepository;
	@Mock
	private OfficeHoursMailService mailService;
	@InjectMocks
	private OfficeHoursReminderService service;

	/** Booked two days ahead and starting within the next day: due for its reminder. */
	private static OfficeHoursMeeting meeting() {
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setId(UUID.randomUUID());
		meeting.setScheduledAt(NOW.plus(Duration.ofHours(20)));
		meeting.setCreatedAt(NOW.minus(Duration.ofDays(2)));
		return meeting;
	}

	private void due(OfficeHoursMeeting... meetings) {
		when(meetingRepository.findReminderDue(NOW, NOW.plus(OfficeHoursReminderService.REMINDER_LEAD)))
				.thenReturn(List.of(meetings));
	}

	@Test
	void remindsBothSides24HoursAheadAndNeverWhatTheOtherServerClaimed() {
		OfficeHoursMeeting mine = meeting();
		OfficeHoursMeeting takenByTheOtherServer = meeting();
		due(mine, takenByTheOtherServer);
		when(meetingRepository.claimStudentReminder(mine.getId(), NOW)).thenReturn(1);
		when(meetingRepository.claimInstructorReminder(mine.getId(), NOW)).thenReturn(1);
		when(meetingRepository.claimStudentReminder(takenByTheOtherServer.getId(), NOW)).thenReturn(0);
		when(meetingRepository.claimInstructorReminder(takenByTheOtherServer.getId(), NOW)).thenReturn(0);
		when(mailService.meetingSoon(any(), any())).thenReturn(Delivery.SENT);

		service.remindUpcomingMeetings(NOW);

		verify(mailService).meetingSoon(mine, Side.STUDENT);
		verify(mailService).meetingSoon(mine, Side.INSTRUCTOR);
		verify(mailService, never()).meetingSoon(takenByTheOtherServer, Side.STUDENT);
		verify(mailService, never()).meetingSoon(takenByTheOtherServer, Side.INSTRUCTOR);
		verify(meetingRepository, never()).releaseStudentReminder(any());
	}

	@Test
	void aFailedSendGivesItsSideBackForTheNextRunAndLeavesTheOtherSideAlone() {
		OfficeHoursMeeting meeting = meeting();
		due(meeting);
		when(meetingRepository.claimStudentReminder(meeting.getId(), NOW)).thenReturn(1);
		when(meetingRepository.claimInstructorReminder(meeting.getId(), NOW)).thenReturn(1);
		when(mailService.meetingSoon(meeting, Side.STUDENT)).thenReturn(Delivery.SENT);
		when(mailService.meetingSoon(meeting, Side.INSTRUCTOR)).thenReturn(Delivery.FAILED);

		service.remindUpcomingMeetings(NOW);

		verify(meetingRepository).releaseInstructorReminder(meeting.getId());
		verify(meetingRepository, never()).releaseStudentReminder(any());
	}

	@Test
	void aSideAlreadyRemindedIsNotTriedAgainAndNothingToSendIsNotRetried() {
		OfficeHoursMeeting meeting = meeting();
		meeting.setStudentRemindedAt(NOW.minusSeconds(60));
		due(meeting);
		when(meetingRepository.claimInstructorReminder(meeting.getId(), NOW)).thenReturn(1);
		when(mailService.meetingSoon(meeting, Side.INSTRUCTOR)).thenReturn(Delivery.NOTHING_TO_SEND);

		service.remindUpcomingMeetings(NOW);

		verify(meetingRepository, never()).claimStudentReminder(any(), any());
		verify(meetingRepository, never()).releaseInstructorReminder(any());
	}

	@Test
	void aMeetingBookedLessThanADayAheadGetsNoReminderSinceTheAcceptanceMailJustWent() {
		OfficeHoursMeeting late = meeting();
		late.setCreatedAt(NOW.minus(Duration.ofHours(2)));
		due(late);

		service.remindUpcomingMeetings(NOW);

		verify(meetingRepository, never()).claimStudentReminder(any(), any());
		verify(meetingRepository, never()).claimInstructorReminder(any(), any());
		verify(mailService, never()).meetingSoon(any(), any());
	}
}
