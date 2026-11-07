package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.entity.OfficeHoursException;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRule;
import com.dps.teknokent.repository.OfficeHoursExceptionRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRuleRepository;

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

    private OfficeHoursSlotService service;

    @BeforeEach
    void setUp() {
        service = new OfficeHoursSlotService(ruleRepository, exceptionRepository, meetingRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(exceptionRepository.findByInstructorIdOrderByStartsOnAsc(ACADEMIC)).thenReturn(List.of());
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(any(), any())).thenReturn(List.of());
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
        OfficeHoursMeeting booked = new OfficeHoursMeeting();
        booked.setScheduledAt(Instant.parse("2026-11-17T11:00:00Z"));
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(ACADEMIC, NOW)).thenReturn(List.of(booked));

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
}
