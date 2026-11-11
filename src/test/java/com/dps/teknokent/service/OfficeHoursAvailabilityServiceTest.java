package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.requests.MentorAvailabilityExceptionRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursAvailabilityRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAvailabilityResponse;
import com.dps.teknokent.entity.OfficeHoursException;
import com.dps.teknokent.entity.OfficeHoursRule;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OfficeHoursEnrollmentRepository;
import com.dps.teknokent.repository.OfficeHoursExceptionRepository;
import com.dps.teknokent.repository.OfficeHoursRuleRepository;
import com.dps.teknokent.repository.OfficeHoursSettingsRepository;

@ExtendWith(MockitoExtension.class)
class OfficeHoursAvailabilityServiceTest {

    private static final UUID INSTRUCTOR = UUID.fromString("00000000-0000-0000-0000-00000000d001");
    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

    @Mock
    private OfficeHoursEnrollmentRepository enrollmentRepository;
    @Mock
    private OfficeHoursSettingsRepository settingsRepository;
    @Mock
    private OfficeHoursRuleRepository ruleRepository;
    @Mock
    private OfficeHoursExceptionRepository exceptionRepository;

    private OfficeHoursAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new OfficeHoursAvailabilityService(enrollmentRepository, settingsRepository, ruleRepository,
                exceptionRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void instructorSomewhere(boolean instructor) {
        when(enrollmentRepository.existsByUserIdAndRole(INSTRUCTOR, OfficeHoursRole.INSTRUCTOR)).thenReturn(instructor);
    }

    private static CreateMentorAvailabilityRuleRequest rule(DayOfWeek day, int from, int to, Integer slot) {
        return new CreateMentorAvailabilityRuleRequest(day, LocalTime.of(from, 0), LocalTime.of(to, 0), slot);
    }

    private static UpdateOfficeHoursAvailabilityRequest request(List<CreateMentorAvailabilityRuleRequest> rules,
            List<MentorAvailabilityExceptionRequest> exceptions) {
        return new UpdateOfficeHoursAvailabilityRequest(rules, exceptions, 5, 30, 10);
    }

    @Test
    void showsAnAcademicTheMentorDefaultsBeforeAnythingIsSaved() {
        instructorSomewhere(true);
        when(settingsRepository.findById(any())).thenReturn(Optional.empty());

        OfficeHoursAvailabilityResponse response = service.find(INSTRUCTOR);

        assertThat(response.rules()).isEmpty();
        assertThat(response.exceptions()).isEmpty();
        assertThat(response.studentCapacity()).isNull();
        assertThat(response.defaultMeetingDurationMinutes()).isEqualTo(45);
        assertThat(response.meetingBufferMinutes()).isEqualTo(15);
    }

    @Test
    void replacesTheWholeSetupAndDeletesTheOldOneFirst() {
        instructorSomewhere(true);
        OfficeHoursRule old = new OfficeHoursRule();
        old.setDay(DayOfWeek.MONDAY);
        old.setStartTime(LocalTime.of(9, 0));
        old.setEndTime(LocalTime.of(10, 0));
        when(ruleRepository.findByInstructorIdOrderByDayOfWeekAscStartTimeAsc(INSTRUCTOR))
                .thenReturn(List.of(old));
        when(exceptionRepository.findByInstructorIdOrderByStartsOnAsc(INSTRUCTOR))
                .thenReturn(List.of());
        when(settingsRepository.findById(INSTRUCTOR)).thenReturn(Optional.empty());

        service.update(INSTRUCTOR, request(
                List.of(rule(DayOfWeek.TUESDAY, 14, 16, 30), rule(DayOfWeek.THURSDAY, 10, 12, null)),
                List.of(new MentorAvailabilityExceptionRequest(LocalDate.of(2026, 11, 20),
                        LocalDate.of(2026, 11, 21), "  Konferans  "))));

        InOrder order = inOrder(ruleRepository);
        order.verify(ruleRepository).deleteAll(List.of(old));
        order.verify(ruleRepository).flush();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OfficeHoursRule>> rules = ArgumentCaptor.forClass(List.class);
        order.verify(ruleRepository).saveAll(rules.capture());
        assertThat(rules.getValue()).extracting(OfficeHoursRule::day, OfficeHoursRule::getSlotMinutes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.TUESDAY, 30),
                        org.assertj.core.groups.Tuple.tuple(DayOfWeek.THURSDAY, 60));
        assertThat(rules.getValue()).allSatisfy(saved -> {
            assertThat(saved.getInstructorId()).isEqualTo(INSTRUCTOR);
            assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OfficeHoursException>> exceptions = ArgumentCaptor.forClass(List.class);
        verify(exceptionRepository).saveAll(exceptions.capture());
        assertThat(exceptions.getValue()).singleElement()
                .satisfies(saved -> assertThat(saved.getReason()).isEqualTo("Konferans"));

        ArgumentCaptor<OfficeHoursSettings> settings = ArgumentCaptor.forClass(OfficeHoursSettings.class);
        verify(settingsRepository).save(settings.capture());
        assertThat(settings.getValue().getInstructorId()).isEqualTo(INSTRUCTOR);
        assertThat(settings.getValue().getStudentCapacity()).isEqualTo(5);
        assertThat(settings.getValue().getDefaultMeetingDurationMinutes()).isEqualTo(30);
        assertThat(settings.getValue().getMeetingBufferMinutes()).isEqualTo(10);
    }

    @Test
    void keepsAnyoneWhoIsNotAnAcademicAwayFromTheSetup() {
        instructorSomewhere(false);

        assertThatThrownBy(() -> service.find(INSTRUCTOR))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.update(INSTRUCTOR, request(List.of(), List.of())))
                .isInstanceOf(ResponseStatusException.class);
        verify(settingsRepository, never()).save(any());
    }

    @Test
    void refusesRangesThatEndBeforeTheyStartOrOverlap() {
        instructorSomewhere(true);

        assertThatThrownBy(() -> service.update(INSTRUCTOR,
                request(List.of(rule(DayOfWeek.MONDAY, 12, 10, null)), List.of())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.update(INSTRUCTOR, request(
                List.of(rule(DayOfWeek.MONDAY, 10, 12, null), rule(DayOfWeek.MONDAY, 11, 13, null)), List.of())))
                .isInstanceOf(InvalidRequestException.class);
        verify(ruleRepository, never()).saveAll(any());
    }

    @Test
    void refusesDatesAwayThatEndBeforeTheyStartOrRepeat() {
        instructorSomewhere(true);
        MentorAvailabilityExceptionRequest backwards = new MentorAvailabilityExceptionRequest(
                LocalDate.of(2026, 11, 21), LocalDate.of(2026, 11, 20), null);
        MentorAvailabilityExceptionRequest day = new MentorAvailabilityExceptionRequest(
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20), null);

        assertThatThrownBy(() -> service.update(INSTRUCTOR, request(List.of(), List.of(backwards))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.update(INSTRUCTOR, request(List.of(), List.of(day, day))))
                .isInstanceOf(InvalidRequestException.class);
    }
}
