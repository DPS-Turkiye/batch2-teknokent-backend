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

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.dto.requests.AnswerOfficeHoursRequest;
import com.dps.teknokent.dto.requests.DecideOfficeHoursRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursAvailabilityRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursProfileRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAcademicianMeetingResponse;
import com.dps.teknokent.dto.responses.OfficeHoursAvailabilityResponse;
import com.dps.teknokent.dto.responses.OfficeHoursInboxRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursProfileResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestMessageRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursSettingsRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;

@ExtendWith(MockitoExtension.class)
class OfficeHoursMeServiceTest {

    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000e001");
    private static final UUID PERSON = UUID.fromString("00000000-0000-0000-0000-00000000e002");
    private static final UUID STUDENT_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000e003");
    private static final UUID STUDENT = UUID.fromString("00000000-0000-0000-0000-00000000e004");
    private static final UUID MOODLE_STUDENT = UUID.fromString("00000000-0000-0000-0000-00000000e005");
    private static final UUID REQUEST = UUID.fromString("00000000-0000-0000-0000-00000000e006");
    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-00000000e007");
    /** Monday 9 November 2026, 09:00 in Turkey. */
    private static final Instant NOW = Instant.parse("2026-11-09T06:00:00Z");
    /** Tuesday 10 November 2026, 14:00 in Turkey. */
    private static final Instant TUESDAY_2PM = Instant.parse("2026-11-10T11:00:00Z");

    @Mock
    private InstitutionPersonRepository institutionPeople;
    @Mock
    private OfficeHoursUserRepository userRepository;
    @Mock
    private OfficeHoursSettingsRepository settingsRepository;
    @Mock
    private OfficeHoursRequestRepository requestRepository;
    @Mock
    private OfficeHoursRequestMessageRepository messageRepository;
    @Mock
    private OfficeHoursMeetingRepository meetingRepository;
    @Mock
    private OfficeHoursCourseRepository courseRepository;
    @Mock
    private OfficeHoursAvailabilityService availabilityService;
    @Mock
    private OfficeHoursBookingService bookingService;
    @Mock
    private OfficeHoursSlotService slotService;

    private OfficeHoursMeService service;

    @BeforeEach
    void setUp() {
        service = new OfficeHoursMeService(institutionPeople, userRepository, settingsRepository, requestRepository,
                messageRepository, meetingRepository, courseRepository, availabilityService, bookingService,
                slotService, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(messageRepository.findByRequestIdInOrderByCreatedAtAsc(any())).thenReturn(List.of());
        lenient().when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of());
        lenient().when(slotService.defaultMeetingMinutes(PERSON)).thenReturn(30);
    }

    private static InstitutionPerson record(UUID accountId, String name, IdentityKind... kinds) {
        AppUser account = new AppUser();
        account.setId(accountId);
        InstitutionPerson person = new InstitutionPerson("odtu", "pid-" + accountId);
        person.setUser(account);
        person.setKinds(Set.of(kinds));
        person.setFullName(name);
        person.setDepartmentName("Bilgisayar Mühendisliği");
        person.setClassYear(kinds.length == 1 && kinds[0] == IdentityKind.STUDENT ? 3 : null);
        person.setSyncedAt(NOW);
        return person;
    }

    private void academic() {
        when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(ACCOUNT))
                .thenReturn(Optional.of(record(ACCOUNT, "Elif Şahin", IdentityKind.ACADEMIC)));
    }

    private static OfficeHoursUser person(UUID id, UUID accountId, String name) {
        OfficeHoursUser person = new OfficeHoursUser();
        person.setId(id);
        person.setAppUserId(accountId);
        person.setFullName(name);
        return person;
    }

    private void hasPerson() {
        when(userRepository.findByAppUserId(ACCOUNT)).thenReturn(Optional.of(person(PERSON, ACCOUNT, "Elif Şahin")));
    }

    private static OfficeHoursRequest request(UUID courseId, UUID studentId, OfficeHoursRequestStatus status) {
        OfficeHoursRequest request = new OfficeHoursRequest();
        request.setId(REQUEST);
        request.setCourseId(courseId);
        request.setStudentId(studentId);
        request.setInstructorId(PERSON);
        request.setStatus(status);
        request.setTopic("Staj");
        request.setExpectedOutcome("CV'ye bakalım");
        request.setPreferredSlots("2026-11-10T14:00");
        request.setCreatedAt(NOW);
        return request;
    }

    @Test
    void isNotThereForSomeoneOdtuDoesNotCallAnAcademic() {
        when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(ACCOUNT))
                .thenReturn(Optional.of(record(ACCOUNT, "Ali Kaya", IdentityKind.STUDENT)));

        assertThatThrownBy(() -> service.inbox(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.saveProfile(ACCOUNT, new UpdateOfficeHoursProfileRequest(true, null, null,
                null))).isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).insertForAccountIfAbsent(any(), any(), any(), any());
    }

    @Test
    void isNotThereForAnAccountWithNoOdtuRecordOrAnInactiveOne() {
        when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(ACCOUNT)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.availability(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);

        InstitutionPerson left = record(ACCOUNT, "Elif Şahin", IdentityKind.ACADEMIC);
        left.setActive(false);
        when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(ACCOUNT)).thenReturn(Optional.of(left));
        assertThatThrownBy(() -> service.meetings(ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void readsAreEmptyAndMakeNobodyBeforeTheFirstSave() {
        academic();
        when(userRepository.findByAppUserId(ACCOUNT)).thenReturn(Optional.empty());

        OfficeHoursAvailabilityResponse availability = service.availability(ACCOUNT);
        assertThat(availability.rules()).isEmpty();
        assertThat(availability.defaultMeetingDurationMinutes()).isEqualTo(45);
        assertThat(service.profile(ACCOUNT).listed()).isFalse();
        assertThat(service.inbox(ACCOUNT)).isEmpty();
        assertThat(service.meetings(ACCOUNT)).isEmpty();
        verify(userRepository, never()).insertForAccountIfAbsent(any(), any(), any(), any());
    }

    @Test
    void theFirstSaveMakesTheirOfficeHoursPersonUnderOdtusName() {
        academic();
        when(userRepository.findByAppUserId(ACCOUNT)).thenReturn(Optional.empty(),
                Optional.of(person(PERSON, ACCOUNT, "Elif Şahin")));
        UpdateOfficeHoursAvailabilityRequest hours = new UpdateOfficeHoursAvailabilityRequest(List.of(), List.of(),
                null, 30, 10);

        service.saveAvailability(ACCOUNT, hours);

        verify(userRepository).insertForAccountIfAbsent(any(), eq(ACCOUNT), eq("Elif Şahin"), eq(NOW));
        verify(availabilityService).write(PERSON, hours);
    }

    @Test
    void savesWhereAndHowStudentsMeetThemAndWhetherTheyAreListed() {
        academic();
        hasPerson();
        when(settingsRepository.findById(PERSON)).thenReturn(Optional.empty());
        when(settingsRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OfficeHoursProfileResponse saved = service.saveProfile(ACCOUNT,
                new UpdateOfficeHoursProfileRequest(true, "  A-204  ", " ", "elif@example.edu"));

        assertThat(saved).isEqualTo(new OfficeHoursProfileResponse(true, "A-204", null, "elif@example.edu"));
        ArgumentCaptor<OfficeHoursSettings> settings = ArgumentCaptor.forClass(OfficeHoursSettings.class);
        verify(settingsRepository).saveAndFlush(settings.capture());
        assertThat(settings.getValue().getInstructorId()).isEqualTo(PERSON);
        assertThat(settings.getValue().getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void showsAMentonStudentsDepartmentAndYearAndAMoodleStudentsNameOnly() {
        academic();
        hasPerson();
        OfficeHoursRequest fromMenton = request(null, STUDENT, OfficeHoursRequestStatus.PENDING);
        OfficeHoursRequest fromCourse = request(COURSE, MOODLE_STUDENT, OfficeHoursRequestStatus.PENDING);
        fromCourse.setId(UUID.randomUUID());
        when(requestRepository.findByInstructorIdOrderByCreatedAtDesc(PERSON))
                .thenReturn(List.of(fromMenton, fromCourse));
        OfficeHoursUser moodle = person(MOODLE_STUDENT, null, "Moodle Öğrenci");
        moodle.setIssuer("https://odtuclass.example");
        moodle.setSubject("42");
        when(userRepository.findAllById(any()))
                .thenReturn(List.of(person(STUDENT, STUDENT_ACCOUNT, "Eski Ad"), moodle));
        when(institutionPeople.findByUserIdIn(List.of(STUDENT_ACCOUNT)))
                .thenReturn(List.of(record(STUDENT_ACCOUNT, "Ali Kaya", IdentityKind.STUDENT)));
        OfficeHoursCourse course = new OfficeHoursCourse();
        course.setId(COURSE);
        course.setTitle("CENG 111");
        when(courseRepository.findAllById(List.of(COURSE))).thenReturn(List.of(course));

        List<OfficeHoursInboxRequestResponse> inbox = service.inbox(ACCOUNT);

        OfficeHoursInboxRequestResponse menton = inbox.get(0);
        assertThat(menton.student().userId()).isEqualTo(STUDENT_ACCOUNT);
        assertThat(menton.student().fullName()).isEqualTo("Ali Kaya");
        assertThat(menton.student().department()).isEqualTo("Bilgisayar Mühendisliği");
        assertThat(menton.student().classYear()).isEqualTo(3);
        assertThat(menton.courseName()).isNull();
        assertThat(menton.note()).isEqualTo("CV'ye bakalım");
        assertThat(menton.requestedTimes())
                .containsExactly(new OfficeHoursTimeResponse(TUESDAY_2PM, TUESDAY_2PM.plusSeconds(30 * 60)));
        assertThat(menton.allowedActions()).containsExactly(OfficeHoursRequestAction.ACCEPT,
                OfficeHoursRequestAction.DECLINE, OfficeHoursRequestAction.PROPOSE_TIMES);

        OfficeHoursInboxRequestResponse course1 = inbox.get(1);
        assertThat(course1.student().userId()).isEqualTo(MOODLE_STUDENT);
        assertThat(course1.student().fullName()).isEqualTo("Moodle Öğrenci");
        assertThat(course1.student().department()).isNull();
        assertThat(course1.student().classYear()).isNull();
        assertThat(course1.courseName()).isEqualTo("CENG 111");
        assertThat(course1.allowedActions()).contains(OfficeHoursRequestAction.ASK_QUESTION);
    }

    @Test
    void anAnsweredRequestOffersNothingMore() {
        academic();
        hasPerson();
        when(requestRepository.findByInstructorIdOrderByCreatedAtDesc(PERSON))
                .thenReturn(List.of(request(null, STUDENT, OfficeHoursRequestStatus.DECLINED)));
        when(userRepository.findAllById(any())).thenReturn(List.of(person(STUDENT, null, "Ali Kaya")));

        assertThat(service.inbox(ACCOUNT).getFirst().allowedActions()).isEmpty();
    }

    @Test
    void acceptingPassesTheStudentsTimeInTurkeyTimeToTheBookingRules() {
        academic();
        hasPerson();
        OfficeHoursRequest pending = request(null, STUDENT, OfficeHoursRequestStatus.PENDING);
        when(requestRepository.findById(REQUEST)).thenReturn(Optional.of(pending));
        when(bookingService.decide(eq(pending), eq(PERSON), any())).thenReturn(pending);
        when(userRepository.findAllById(any())).thenReturn(List.of(person(STUDENT, null, "Ali Kaya")));

        service.answer(ACCOUNT, REQUEST, new AnswerOfficeHoursRequest(OfficeHoursRequestAction.ACCEPT, TUESDAY_2PM,
                null, "Görüşürüz"));

        verify(bookingService).decide(pending, PERSON, new DecideOfficeHoursRequest(
                OfficeHoursRequestStatus.ACCEPTED, "Görüşürüz", "2026-11-10T14:00", null));
    }

    @Test
    void offeringOtherTimesPassesUpToThreeOfThem() {
        academic();
        hasPerson();
        OfficeHoursRequest pending = request(null, STUDENT, OfficeHoursRequestStatus.PENDING);
        when(requestRepository.findById(REQUEST)).thenReturn(Optional.of(pending));
        when(bookingService.decide(eq(pending), eq(PERSON), any())).thenReturn(pending);
        when(userRepository.findAllById(any())).thenReturn(List.of(person(STUDENT, null, "Ali Kaya")));

        service.answer(ACCOUNT, REQUEST, new AnswerOfficeHoursRequest(OfficeHoursRequestAction.PROPOSE_TIMES, null,
                List.of(TUESDAY_2PM, TUESDAY_2PM.plusSeconds(3600)), null));

        verify(bookingService).decide(pending, PERSON, new DecideOfficeHoursRequest(
                OfficeHoursRequestStatus.COUNTER_PROPOSED, null, null,
                List.of("2026-11-10T14:00", "2026-11-10T15:00")));
    }

    @Test
    void asksNoQuestionOnARequestFromMentonWhereTheStudentCannotAnswer() {
        academic();
        hasPerson();
        when(requestRepository.findById(REQUEST))
                .thenReturn(Optional.of(request(null, STUDENT, OfficeHoursRequestStatus.PENDING)));

        assertThatThrownBy(() -> service.answer(ACCOUNT, REQUEST, new AnswerOfficeHoursRequest(
                OfficeHoursRequestAction.ASK_QUESTION, null, null, "Hangi ders?")))
                .isInstanceOf(InvalidRequestException.class);
        verify(bookingService, never()).decide(any(OfficeHoursRequest.class), any(), any());
    }

    @Test
    void anotherAcademiciansRequestIsNotFound() {
        academic();
        hasPerson();
        OfficeHoursRequest someoneElses = request(null, STUDENT, OfficeHoursRequestStatus.PENDING);
        someoneElses.setInstructorId(UUID.randomUUID());
        when(requestRepository.findById(REQUEST)).thenReturn(Optional.of(someoneElses));

        assertThatThrownBy(() -> service.answer(ACCOUNT, REQUEST, new AnswerOfficeHoursRequest(
                OfficeHoursRequestAction.DECLINE, null, null, null))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anAcademicianWhoNeverSavedAnythingHasNoRequestToAnswer() {
        academic();
        when(userRepository.findByAppUserId(ACCOUNT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.answer(ACCOUNT, REQUEST, new AnswerOfficeHoursRequest(
                OfficeHoursRequestAction.DECLINE, null, null, null))).isInstanceOf(ResourceNotFoundException.class);
        verify(requestRepository, never()).findById(any());
    }

    @Test
    void listsMeetingsWithTheirEndAndWhereThePlaceIs() {
        academic();
        hasPerson();
        OfficeHoursMeeting meeting = new OfficeHoursMeeting();
        meeting.setId(UUID.randomUUID());
        meeting.setRequestId(REQUEST);
        meeting.setInstructorId(PERSON);
        meeting.setStudentId(STUDENT);
        meeting.setScheduledAt(TUESDAY_2PM);
        meeting.setDurationMinutes(45);
        when(meetingRepository.findByInstructorIdOrderByScheduledAtAsc(PERSON)).thenReturn(List.of(meeting));
        when(requestRepository.findAllById(List.of(REQUEST)))
                .thenReturn(List.of(request(null, STUDENT, OfficeHoursRequestStatus.ACCEPTED)));
        when(userRepository.findAllById(any())).thenReturn(List.of(person(STUDENT, null, "Ali Kaya")));
        OfficeHoursSettings settings = new OfficeHoursSettings();
        settings.setDefaultPlace("A-204");
        settings.setOnlineLink("https://meet.example/x");
        when(settingsRepository.findById(PERSON)).thenReturn(Optional.of(settings));

        OfficeHoursAcademicianMeetingResponse only = service.meetings(ACCOUNT).getFirst();

        assertThat(only.endsAt()).isEqualTo(TUESDAY_2PM.plusSeconds(45 * 60));
        assertThat(only.topic()).isEqualTo("Staj");
        assertThat(only.place()).isEqualTo("A-204");
        assertThat(only.onlineLink()).isEqualTo("https://meet.example/x");
        assertThat(only.courseName()).isNull();
    }
}
