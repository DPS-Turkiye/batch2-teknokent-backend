package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.OfficeHoursMessageType;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.AcceptOfficeHoursSlotRequest;
import com.dps.teknokent.dto.requests.CreateOfficeHoursRequest;
import com.dps.teknokent.dto.requests.DecideOfficeHoursRequest;
import com.dps.teknokent.dto.requests.ReplyOfficeHoursRequest;
import com.dps.teknokent.dto.responses.OfficeHoursRequestResponse;
import com.dps.teknokent.entity.OfficeHoursEnrollment;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursRequestMessage;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.OfficeHoursEnrollmentRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestMessageRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursSettingsRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;

@ExtendWith(MockitoExtension.class)
class OfficeHoursBookingServiceTest {

    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-00000000f001");
    private static final UUID STUDENT = UUID.fromString("00000000-0000-0000-0000-00000000f002");
    private static final UUID ACADEMIC = UUID.fromString("00000000-0000-0000-0000-00000000f003");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000f004");
    /** Monday 9 November 2026, 09:00 in Turkey. */
    private static final Instant NOW = Instant.parse("2026-11-09T06:00:00Z");
    private static final String TUESDAY_2PM = "2026-11-10T14:00";
    private static final String TUESDAY_3PM = "2026-11-10T15:00";

    @Mock
    private OfficeHoursEnrollmentRepository enrollmentRepository;
    @Mock
    private OfficeHoursUserRepository userRepository;
    @Mock
    private OfficeHoursRequestRepository requestRepository;
    @Mock
    private OfficeHoursRequestMessageRepository messageRepository;
    @Mock
    private OfficeHoursMeetingRepository meetingRepository;
    @Mock
    private OfficeHoursSettingsRepository settingsRepository;
    @Mock
    private OfficeHoursSlotService slotService;

    private OfficeHoursBookingService service;

    @BeforeEach
    void setUp() {
        service = new OfficeHoursBookingService(enrollmentRepository, userRepository, requestRepository,
                messageRepository, meetingRepository, settingsRepository, slotService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(requestRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(messageRepository.findByRequestIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of());
    }

    private void enrolled(UUID userId, OfficeHoursRole role) {
        OfficeHoursEnrollment enrollment = new OfficeHoursEnrollment();
        enrollment.setUserId(userId);
        enrollment.setCourseId(COURSE);
        enrollment.setRole(role);
        lenient().when(enrollmentRepository.findByUserIdAndCourseId(userId, COURSE))
                .thenReturn(Optional.of(enrollment));
    }

    private OfficeHoursRequest existing(OfficeHoursRequestStatus status) {
        OfficeHoursRequest request = new OfficeHoursRequest();
        request.setId(REQUEST);
        request.setCourseId(COURSE);
        request.setStudentId(STUDENT);
        request.setInstructorId(ACADEMIC);
        request.setStatus(status);
        request.setTopic("Ödev 2");
        request.setPreferredSlots(TUESDAY_2PM + "," + TUESDAY_3PM);
        request.setCreatedAt(NOW);
        when(requestRepository.findById(REQUEST)).thenReturn(Optional.of(request));
        return request;
    }

    @Test
    void aStudentAsksAnAcademicOfTheCourseForUpToThreeTimes() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);

        OfficeHoursRequestResponse response = service.create(STUDENT, COURSE, new CreateOfficeHoursRequest(
                ACADEMIC, "  Ödev 2 hakkında  ", " ", List.of(TUESDAY_2PM, TUESDAY_2PM, TUESDAY_3PM)));

        assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.PENDING);
        assertThat(response.topic()).isEqualTo("Ödev 2 hakkında");
        assertThat(response.expectedOutcome()).isNull();
        assertThat(response.preferredSlots()).containsExactly(TUESDAY_2PM, TUESDAY_3PM);
    }

    @Test
    void refusesTimesSoonerThanFourHours() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);

        assertThatThrownBy(() -> service.create(STUDENT, COURSE,
                new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of("2026-11-09T11:00"))))
                .isInstanceOf(InvalidRequestException.class);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void onlyStudentsRequestAndOnlyFromTheCoursesAcademics() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.STUDENT);

        assertThatThrownBy(() -> service.create(STUDENT, COURSE,
                new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM))))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void acceptingOneOfTheStudentsTimesMakesTheMeetingWithTheSlotLength() {
        existing(OfficeHoursRequestStatus.PENDING);
        Instant at = Instant.parse("2026-11-10T12:00:00Z");
        when(slotService.openSlotAt(ACADEMIC, at)).thenReturn(Optional.of(new OfficeHoursSlotService.Slot(at, 20)));

        OfficeHoursRequestResponse response = service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, "Görüşürüz", TUESDAY_3PM, null));

        assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.ACCEPTED);
        ArgumentCaptor<OfficeHoursMeeting> meeting = ArgumentCaptor.forClass(OfficeHoursMeeting.class);
        verify(meetingRepository).saveAndFlush(meeting.capture());
        assertThat(meeting.getValue().getScheduledAt()).isEqualTo(at);
        assertThat(meeting.getValue().getDurationMinutes()).isEqualTo(20);
        assertThat(meeting.getValue().getStudentId()).isEqualTo(STUDENT);
    }

    @Test
    void acceptingWithoutATimeTakesTheFirstFreeOneAndATypedTimeGetsTheDefaultLength() {
        existing(OfficeHoursRequestStatus.PENDING);
        when(meetingRepository.existsByInstructorIdAndScheduledAt(ACADEMIC, Instant.parse("2026-11-10T11:00:00Z")))
                .thenReturn(true);
        when(slotService.openSlotAt(any(), any())).thenReturn(Optional.empty());
        when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.empty());

        service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, null, null, null));

        ArgumentCaptor<OfficeHoursMeeting> meeting = ArgumentCaptor.forClass(OfficeHoursMeeting.class);
        verify(meetingRepository).saveAndFlush(meeting.capture());
        assertThat(meeting.getValue().getScheduledAt()).isEqualTo(Instant.parse("2026-11-10T12:00:00Z"));
        assertThat(meeting.getValue().getDurationMinutes()).isEqualTo(45);
    }

    @Test
    void refusesToAcceptATimeTheStudentDidNotAskFor() {
        existing(OfficeHoursRequestStatus.PENDING);

        assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, null, "2026-11-11T10:00", null)))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void refusesADoubleBooking() {
        existing(OfficeHoursRequestStatus.PENDING);
        when(meetingRepository.existsByInstructorIdAndScheduledAt(ACADEMIC, Instant.parse("2026-11-10T11:00:00Z")))
                .thenReturn(true);

        assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, null, TUESDAY_2PM, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void onlyTheAcademicItWasSentToAnswers() {
        existing(OfficeHoursRequestStatus.PENDING);

        assertThatThrownBy(() -> service.decide(STUDENT, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, null, null, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void decliningKeepsTheReason() {
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);

        service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, " Bu hafta yokum ", null, null));

        assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.DECLINED);
        assertThat(request.getRejectionReason()).isEqualTo("Bu hafta yokum");
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    void aQuestionWaitsForTheStudentsAnswerThenTheRequestIsPendingAgain() {
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);

        service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.MORE_INFO_REQUESTED, "Hangi soru?", null, null));
        assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.MORE_INFO_REQUESTED);

        service.reply(STUDENT, COURSE, REQUEST, new ReplyOfficeHoursRequest("3. soru"));

        assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.PENDING);
        ArgumentCaptor<OfficeHoursRequestMessage> messages = ArgumentCaptor.forClass(OfficeHoursRequestMessage.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(messages.capture());
        assertThat(messages.getAllValues()).extracting(OfficeHoursRequestMessage::getType)
                .containsExactly(OfficeHoursMessageType.INSTRUCTOR_QUESTION, OfficeHoursMessageType.STUDENT_REPLY);
    }

    @Test
    void theStudentTakesOneOfTheProposedTimes() {
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);
        when(slotService.openSlotAt(any(), any())).thenReturn(Optional.empty());
        when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.empty());

        service.decide(ACADEMIC, COURSE, REQUEST, new DecideOfficeHoursRequest(
                OfficeHoursRequestStatus.COUNTER_PROPOSED, "Şunlar uygun", null, List.of("2026-11-12T10:00")));
        assertThat(request.getProposedSlots()).isEqualTo("2026-11-12T10:00");

        assertThatThrownBy(() -> service.acceptProposedSlot(STUDENT, COURSE, REQUEST,
                new AcceptOfficeHoursSlotRequest(TUESDAY_2PM))).isInstanceOf(InvalidRequestException.class);
        service.acceptProposedSlot(STUDENT, COURSE, REQUEST, new AcceptOfficeHoursSlotRequest("2026-11-12T10:00"));

        assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.ACCEPTED);
        verify(meetingRepository).saveAndFlush(any());
    }

    @Test
    void aProposalNeedsATime() {
        existing(OfficeHoursRequestStatus.PENDING);

        assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.COUNTER_PROPOSED, null, null, List.of())))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void theStudentWithdrawsAnOpenRequestOnly() {
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.COUNTER_PROPOSED);

        service.withdraw(STUDENT, COURSE, REQUEST);
        assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.WITHDRAWN);

        assertThatThrownBy(() -> service.withdraw(STUDENT, COURSE, REQUEST))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void someoneElseCannotWithdrawOrAnswer() {
        existing(OfficeHoursRequestStatus.MORE_INFO_REQUESTED);

        assertThatThrownBy(() -> service.withdraw(ACADEMIC, COURSE, REQUEST))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.reply(ACADEMIC, COURSE, REQUEST, new ReplyOfficeHoursRequest("x")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aStudentSeesTheirRequestsAndAnAcademicTheOnesSentToThem() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
        when(requestRepository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(COURSE, STUDENT)).thenReturn(List.of());
        when(requestRepository.findByCourseIdAndInstructorIdOrderByCreatedAtDesc(COURSE, ACADEMIC))
                .thenReturn(List.of());

        service.requests(STUDENT, COURSE);
        service.requests(ACADEMIC, COURSE);

        verify(requestRepository).findByCourseIdAndStudentIdOrderByCreatedAtDesc(COURSE, STUDENT);
        verify(requestRepository).findByCourseIdAndInstructorIdOrderByCreatedAtDesc(COURSE, ACADEMIC);
    }
}
