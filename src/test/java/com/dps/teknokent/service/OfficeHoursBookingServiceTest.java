package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.AcceptOfficeHoursSlotRequest;
import com.dps.teknokent.dto.requests.CreateOfficeHoursRequest;
import com.dps.teknokent.dto.requests.DecideOfficeHoursRequest;
import com.dps.teknokent.dto.requests.ReplyOfficeHoursRequest;
import com.dps.teknokent.dto.responses.OfficeHoursMeetingResponse;
import com.dps.teknokent.dto.responses.OfficeHoursRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursEnrollment;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursRequestMessage;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
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
    @Mock
    private OfficeHoursCourseRepository courseRepository;

    private OfficeHoursBookingService service;

    @BeforeEach
    void setUp() {
        service = new OfficeHoursBookingService(enrollmentRepository, userRepository, requestRepository,
                messageRepository, meetingRepository, settingsRepository, slotService, courseRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(requestRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(userRepository.findAllById(any())).thenReturn(List.of());
        lenient().when(messageRepository.findByRequestIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of());
        lenient().when(courseRepository.findAllById(any())).thenReturn(List.of(course()));
    }

    private static OfficeHoursCourse course() {
        OfficeHoursCourse course = new OfficeHoursCourse();
        course.setId(COURSE);
        course.setTitle("CENG 242 Programlama Dilleri");
        return course;
    }

    private void enrolled(UUID userId, OfficeHoursRole role) {
        OfficeHoursEnrollment enrollment = new OfficeHoursEnrollment();
        enrollment.setUserId(userId);
        enrollment.setCourseId(COURSE);
        enrollment.setRole(role);
        lenient().when(enrollmentRepository.findByUserIdAndCourseId(userId, COURSE))
                .thenReturn(Optional.of(enrollment));
    }

    private static OfficeHoursSettings capacity(int students) {
        OfficeHoursSettings settings = new OfficeHoursSettings();
        settings.setStudentCapacity(students);
        return settings;
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
        // Lists read requests by course, not by id.
        lenient().when(requestRepository.findById(REQUEST)).thenReturn(Optional.of(request));
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
    void anAcademicAtTheirStudentLimitTakesNoNewStudent() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
        when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(capacity(1)));
        OfficeHoursRequest someoneElse = new OfficeHoursRequest();
        someoneElse.setStudentId(UUID.randomUUID());
        when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of(someoneElse));
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(ACADEMIC, NOW)).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(STUDENT, COURSE,
                new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM))))
                .isInstanceOf(ConflictException.class);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void aStudentAlreadyWorkingWithTheAcademicCanStillAsk() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
        when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(capacity(1)));
        when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of());
        OfficeHoursMeeting own = new OfficeHoursMeeting();
        own.setStudentId(STUDENT);
        when(meetingRepository.findByInstructorIdAndScheduledAtAfter(ACADEMIC, NOW)).thenReturn(List.of(own));

        OfficeHoursRequestResponse response = service.create(STUDENT, COURSE,
                new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM)));

        assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.PENDING);
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
        when(slotService.openSlotAt(any(), any())).thenReturn(Optional.empty());
        when(slotService.defaultMeetingMinutes(ACADEMIC)).thenReturn(45);
        when(slotService.clashesWithCalendar(ACADEMIC, Instant.parse("2026-11-10T11:00:00Z"), 45)).thenReturn(true);

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
    void refusesATimeThatOverlapsAMeetingAlreadyOnTheCalendar() {
        existing(OfficeHoursRequestStatus.PENDING);
        when(slotService.openSlotAt(any(), any())).thenReturn(Optional.empty());
        when(slotService.defaultMeetingMinutes(ACADEMIC)).thenReturn(45);
        when(slotService.clashesWithCalendar(ACADEMIC, Instant.parse("2026-11-10T11:00:00Z"), 45)).thenReturn(true);

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
        when(slotService.defaultMeetingMinutes(ACADEMIC)).thenReturn(45);

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

    @Test
    void aCoursesRoutesNeverReachARequestMadeFromMenton() {
        OfficeHoursRequest fromMenton = existing(OfficeHoursRequestStatus.PENDING);
        fromMenton.setCourseId(null);

        assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.withdraw(STUDENT, COURSE, REQUEST))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aTimeAnotherOpenRequestHoldsIsNotAskedForOrOfferedAgain() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
        Instant held = Instant.parse("2026-11-10T11:00:00Z");
        when(slotService.heldTimes(ACADEMIC, null)).thenReturn(Set.of(held));

        assertThatThrownBy(() -> service.create(STUDENT, COURSE, new CreateOfficeHoursRequest(
                ACADEMIC, "Ödev 2", null, List.of(TUESDAY_2PM)))).isInstanceOf(ConflictException.class);

        existing(OfficeHoursRequestStatus.PENDING);
        when(slotService.heldTimes(ACADEMIC, REQUEST)).thenReturn(Set.of(held));
        assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST, new DecideOfficeHoursRequest(
                OfficeHoursRequestStatus.COUNTER_PROPOSED, null, null, List.of(TUESDAY_2PM))))
                .isInstanceOf(ConflictException.class);
        verify(requestRepository, never()).saveAndFlush(any());
    }

    @Test
    void requestsCarryTheCourseTimesWithTurkeysOffsetAndWhatTheCallerMayDo() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);
        when(requestRepository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(COURSE, STUDENT))
                .thenReturn(List.of(request));
        when(requestRepository.findByCourseIdAndInstructorIdOrderByCreatedAtDesc(COURSE, ACADEMIC))
                .thenReturn(List.of(request));
        // One look at the calendar per academic: the 14:00 slot is 20 minutes; 15:00 is no slot, so the default.
        when(slotService.openSlots(ACADEMIC)).thenReturn(
                List.of(new OfficeHoursSlotService.Slot(Instant.parse("2026-11-10T11:00:00Z"), 20)));
        when(slotService.defaultMeetingMinutes(ACADEMIC)).thenReturn(45);

        OfficeHoursRequestResponse asStudent = service.requests(STUDENT, COURSE).getFirst();
        OfficeHoursRequestResponse asAcademic = service.requests(ACADEMIC, COURSE).getFirst();

        assertThat(asStudent.courseName()).isEqualTo("CENG 242 Programlama Dilleri");
        assertThat(asStudent.preferredSlots()).containsExactly(TUESDAY_2PM, TUESDAY_3PM);
        assertThat(asStudent.preferredTimes()).containsExactly(
                new OfficeHoursTimeResponse(OffsetDateTime.parse("2026-11-10T14:00+03:00"),
                        OffsetDateTime.parse("2026-11-10T14:20+03:00")),
                new OfficeHoursTimeResponse(OffsetDateTime.parse("2026-11-10T15:00+03:00"),
                        OffsetDateTime.parse("2026-11-10T15:45+03:00")));
        assertThat(asStudent.proposedTimes()).isEmpty();
        assertThat(asStudent.allowedActions()).containsExactly(OfficeHoursRequestAction.WITHDRAW);
        assertThat(asAcademic.allowedActions()).containsExactly(OfficeHoursRequestAction.ACCEPT,
                OfficeHoursRequestAction.DECLINE, OfficeHoursRequestAction.PROPOSE_TIMES,
                OfficeHoursRequestAction.ASK_QUESTION);
    }

    @Test
    void anAnswerShowsWhatIsLeftForTheAcademic() {
        enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
        existing(OfficeHoursRequestStatus.PENDING);

        OfficeHoursRequestResponse response = service.decide(ACADEMIC, COURSE, REQUEST,
                new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, null, null, null));

        assertThat(response.allowedActions()).isEmpty();
    }

    @Test
    void upcomingMeetingsCarryTheirTimeWithTheOffsetAndTheCourse() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.ACCEPTED);
        when(requestRepository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(COURSE, STUDENT))
                .thenReturn(List.of(request));
        OfficeHoursMeeting meeting = new OfficeHoursMeeting();
        meeting.setId(UUID.randomUUID());
        meeting.setRequestId(REQUEST);
        meeting.setCourseId(COURSE);
        meeting.setStudentId(STUDENT);
        meeting.setInstructorId(ACADEMIC);
        meeting.setScheduledAt(Instant.parse("2026-11-10T11:00:00Z"));
        meeting.setDurationMinutes(30);
        when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of(meeting));

        OfficeHoursMeetingResponse response = service.upcomingMeetings(STUDENT, COURSE).getFirst();

        assertThat(response.time()).isEqualTo(new OfficeHoursTimeResponse(
                OffsetDateTime.parse("2026-11-10T14:00+03:00"), OffsetDateTime.parse("2026-11-10T14:30+03:00")));
        assertThat(response.courseName()).isEqualTo("CENG 242 Programlama Dilleri");
        assertThat(response.allowedActions()).isEmpty();
    }

    @Test
    void aMeetingWithoutACourseHasNoCourseNameInsteadOfFailing() {
        enrolled(STUDENT, OfficeHoursRole.STUDENT);
        OfficeHoursRequest request = existing(OfficeHoursRequestStatus.ACCEPTED);
        when(requestRepository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(COURSE, STUDENT))
                .thenReturn(List.of(request));
        OfficeHoursMeeting meeting = new OfficeHoursMeeting();
        meeting.setId(UUID.randomUUID());
        meeting.setRequestId(REQUEST);
        meeting.setStudentId(STUDENT);
        meeting.setInstructorId(ACADEMIC);
        meeting.setScheduledAt(Instant.parse("2026-11-10T11:00:00Z"));
        meeting.setDurationMinutes(30);
        when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of(meeting));

        assertThat(service.upcomingMeetings(STUDENT, COURSE).getFirst().courseName()).isNull();
    }
}
