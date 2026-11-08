package com.dps.teknokent.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

@ExtendWith(MockitoExtension.class)
class OfficeHoursReminderServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T09:00:00Z");

    @Mock
    private OfficeHoursMeetingRepository meetingRepository;
    @Mock
    private OfficeHoursMailService mailService;
    @InjectMocks
    private OfficeHoursReminderService service;

    private static OfficeHoursMeeting meeting() {
        OfficeHoursMeeting meeting = new OfficeHoursMeeting();
        meeting.setId(UUID.randomUUID());
        return meeting;
    }

    @Test
    void remindsMeetingsStartingWithinTheMentorLeadOnceEvenWithTwoServers() {
        OfficeHoursMeeting mine = meeting();
        OfficeHoursMeeting takenByTheOtherServer = meeting();
        when(meetingRepository.findByReminderSentAtIsNullAndCancelledAtIsNullAndScheduledAtBetween(NOW,
                NOW.plus(NotificationReminderService.MEETING_LEAD))).thenReturn(List.of(mine, takenByTheOtherServer));
        when(meetingRepository.markReminded(mine.getId(), NOW)).thenReturn(1);
        when(meetingRepository.markReminded(takenByTheOtherServer.getId(), NOW)).thenReturn(0);

        service.remindUpcomingMeetings(NOW);

        verify(mailService).meetingSoon(mine);
        verify(mailService, never()).meetingSoon(takenByTheOtherServer);
    }
}
