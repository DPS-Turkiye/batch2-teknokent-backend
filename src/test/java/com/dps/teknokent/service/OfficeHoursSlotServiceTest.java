package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

@ExtendWith(MockitoExtension.class)
class OfficeHoursSlotServiceTest {

    private static final UUID ACADEMIC = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    /** Monday 9 November 2026, 09:00 in Turkey. */
    private static final Instant NOW = Instant.parse("2026-11-09T06:00:00Z");

    @Mock
    private OfficeHoursRuleRepository ruleRepository;
    @Mock
    private OfficeHoursExceptionRepository exceptionRepository;
    @Mock
    private OfficeHoursMeetingRepository meetingRepository;
    @Mock
    private OfficeHoursSettingsRepository settingsRepository;
    @Mock
    private OfficeHoursRequestRepository requestRepository;

    private OfficeHoursSlotService service;

    @BeforeEach
    void setUp() {
        service = new OfficeHoursSlotService(ruleRepository, exceptionRepository, meetingRepository,
                settingsRepository, requestRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(settings(0)));
        lenient().when(exceptionRepository.findByInstructorIdOrderByStartsOnAsc(ACADEMIC)).thenReturn(List.of());
        lenient().when(meetingRepository.findByInstructorIdAndScheduledAtAfter(any(), any())).thenReturn(List.of());
        lenient().when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of());
    }

    private static OfficeHoursRequest open(UUID id, OfficeHoursRequestStatus status, String preferred,
            String proposed) {
        OfficeHoursRequest request = new OfficeHoursRequest();
        request.setId(id);
        request.setStatus(status);
        request.setPreferredSlots(preferred);
        request.setProposedSlots(proposed);
        return request;
    }

    private static OfficeHoursSettings settings(int bufferMinutes) {
        OfficeHoursSettings settings = new OfficeHoursSettings();
        settings.setMeetingBufferMinutes(bufferMinutes);
        return settings;
    }

    private static OfficeHoursMeeting meeting(String startsAt, int minutes) {
        OfficeHoursMeeting meeting = new OfficeHoursMeeting();
        meeting.setScheduledAt(Instant.parse(startsAt));
        meeting.setDurationMinutes(minutes);
        return meeting;
    }

    private void rules(OfficeHoursRule... rules) {
        when(ruleRepository.findByInstructorIdOrderByDayOfWeekAscStartTimeAsc(ACADEMIC)).thenReturn(List.of(rules));
    }

    private static OfficeHoursRule rule(DayOfWeek day, int from, int to, int slotMinutes) {
        OfficeHoursRule rule = new OfficeHoursRule();
        rule.setDay(day);
        rule.setStartTime(LocalTime.of(from, 0));
        rule.setEndTime(LocalTime.of(to, 0));
        rule.setSlotMinutes(slotMinutes);
        return rule;
    }

    @Test
    void splitsEachWeeklyRangeIntoSlotsForEightWeeks() {
        rules(rule(DayOfWeek.TUESDAY, 14, 15, 30));

        List<OfficeHoursSlotService.Slot> slots = service.openSlots(ACADEMIC);

        assertThat(slots).hasSize(16);
        assertThat(slots.get(0).startsAt()).isEqualTo(Instant.parse("2026-11-10T11:00:00Z"));
        assertThat(slots.get(1).startsAt()).isEqualTo(Instant.parse("2026-11-10T11:30:00Z"));
        assertThat(slots.get(0).durationMinutes()).isEqualTo(30);
    }

    @Test
    void leavesOutTimesSoonerThanFourHours() {
        rules(rule(DayOfWeek.MONDAY, 10, 14, 60));

        List<OfficeHoursSlotService.Slot> slots = service.openSlots(ACADEMIC);

        // Today 10:00-12:00 are within four hours of 09:00; 13:00 is the first one left today.
        assertThat(slots.get(0).startsAt()).isEqualTo(Instant.parse("2026-11-09T10:00:00Z"));
    }

    @Test
    void leavesOutDatesAwayAndBookedTimes() {
        rules(rule(DayOfWeek.TUESDAY, 14, 15, 60));
        OfficeHoursException away = new OfficeHoursException();
        away.setStartsOn(LocalDate.of(2026, 11, 10));
        away.setEndsOn(LocalDate.of(2026, 11, 10));
        when(exceptionRepository.findByInstructorIdOrderByStartsOnAsc(ACADEMIC)).thenReturn(List.of(away));
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(eq(ACADEMIC), any()))
                .thenReturn(List.of(meeting("2026-11-17T11:00:00Z", 60)));

        List<OfficeHoursSlotService.Slot> slots = service.openSlots(ACADEMIC);

        assertThat(slots).extracting(OfficeHoursSlotService.Slot::startsAt)
                .doesNotContain(Instant.parse("2026-11-10T11:00:00Z"), Instant.parse("2026-11-17T11:00:00Z"))
                .contains(Instant.parse("2026-11-24T11:00:00Z"));
    }

    @Test
    void findsTheOpenSlotAtATime() {
        rules(rule(DayOfWeek.TUESDAY, 14, 15, 20));

        assertThat(service.openSlotAt(ACADEMIC, Instant.parse("2026-11-10T11:20:00Z")))
                .hasValueSatisfying(slot -> assertThat(slot.durationMinutes()).isEqualTo(20));
        assertThat(service.openSlotAt(ACADEMIC, Instant.parse("2026-11-10T11:10:00Z"))).isEmpty();
    }

    @Test
    void leavesOutEverySlotAMeetingOverlapsNotOnlyTheOneItStartsIn() {
        rules(rule(DayOfWeek.TUESDAY, 14, 16, 30));
        // A one-hour meeting at 14:00 (11:00Z) covers the 14:00 and 14:30 slots.
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(eq(ACADEMIC), any()))
                .thenReturn(List.of(meeting("2026-11-10T11:00:00Z", 60)));

        List<Instant> tuesday = service.openSlots(ACADEMIC).stream().map(OfficeHoursSlotService.Slot::startsAt)
                .filter(at -> at.toString().startsWith("2026-11-10")).toList();

        assertThat(tuesday).containsExactly(Instant.parse("2026-11-10T12:00:00Z"), Instant.parse("2026-11-10T12:30:00Z"));
    }

    @Test
    void keepsTheBufferAroundAMeetingFree() {
        rules(rule(DayOfWeek.TUESDAY, 14, 17, 30));
        when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(settings(30)));
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(eq(ACADEMIC), any()))
                .thenReturn(List.of(meeting("2026-11-10T12:00:00Z", 30)));

        List<Instant> tuesday = service.openSlots(ACADEMIC).stream().map(OfficeHoursSlotService.Slot::startsAt)
                .filter(at -> at.toString().startsWith("2026-11-10")).toList();

        // The 15:00-15:30 meeting with a 30-minute buffer blocks 14:30 and 15:30 as well as 15:00.
        assertThat(tuesday).containsExactly(Instant.parse("2026-11-10T11:00:00Z"),
                Instant.parse("2026-11-10T13:00:00Z"), Instant.parse("2026-11-10T13:30:00Z"));
    }

    @Test
    void saysWhetherATypedTimeClashesWithTheCalendar() {
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(eq(ACADEMIC), any()))
                .thenReturn(List.of(meeting("2026-11-10T11:00:00Z", 60)));

        assertThat(service.clashesWithCalendar(ACADEMIC, Instant.parse("2026-11-10T11:30:00Z"), 45)).isTrue();
        assertThat(service.clashesWithCalendar(ACADEMIC, Instant.parse("2026-11-10T10:30:00Z"), 45)).isTrue();
        assertThat(service.clashesWithCalendar(ACADEMIC, Instant.parse("2026-11-10T12:00:00Z"), 45)).isFalse();
        assertThat(service.clashesWithCalendar(ACADEMIC, Instant.parse("2026-11-10T10:15:00Z"), 45)).isFalse();
    }

    @Test
    void anOpenRequestHoldsItsTimesSoNoOneElseIsOfferedThem() {
        rules(rule(DayOfWeek.TUESDAY, 14, 16, 60));
        UUID asking = UUID.randomUUID();
        UUID offered = UUID.randomUUID();
        when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of(
                open(asking, OfficeHoursRequestStatus.PENDING, "2026-11-10T14:00", null),
                // The academic offered 15:00 instead; the student's own 14:00 no longer holds anything.
                open(offered, OfficeHoursRequestStatus.COUNTER_PROPOSED, "2026-11-17T14:00", "2026-11-10T15:00")));

        List<Instant> starts = service.openSlots(ACADEMIC).stream().map(OfficeHoursSlotService.Slot::startsAt)
                .toList();

        assertThat(starts).doesNotContain(Instant.parse("2026-11-10T11:00:00Z"), Instant.parse("2026-11-10T12:00:00Z"))
                .contains(Instant.parse("2026-11-17T11:00:00Z"));
        assertThat(service.heldTimes(ACADEMIC, asking)).containsExactly(Instant.parse("2026-11-10T12:00:00Z"));
    }

    @Test
    void aHeldTimeStillHasItsSlotLengthWhenItsOwnRequestIsAccepted() {
        rules(rule(DayOfWeek.TUESDAY, 14, 16, 45));
        lenient().when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of(
                open(UUID.randomUUID(), OfficeHoursRequestStatus.PENDING, "2026-11-10T14:00", null)));

        assertThat(service.openSlotAt(ACADEMIC, Instant.parse("2026-11-10T11:00:00Z")))
                .map(OfficeHoursSlotService.Slot::durationMinutes).contains(45);
    }
}
