package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.dps.teknokent.domain.OfficeHoursMeetingAction;
import com.dps.teknokent.domain.OfficeHoursMessageType;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.AcceptOfficeHoursSlotRequest;
import com.dps.teknokent.dto.requests.CancelOfficeHoursMeetingRequest;
import com.dps.teknokent.dto.requests.CreateOfficeHoursRequest;
import com.dps.teknokent.dto.requests.DecideOfficeHoursRequest;
import com.dps.teknokent.dto.requests.ReplyOfficeHoursRequest;
import com.dps.teknokent.dto.responses.OfficeHoursMeetingResponse;
import com.dps.teknokent.dto.responses.OfficeHoursPersonResponse;
import com.dps.teknokent.dto.responses.OfficeHoursRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursSlotResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursEnrollment;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursRequestMessage;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.entity.OfficeHoursUser;
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
import com.dps.teknokent.service.OfficeHoursMailService.Event;

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
	@Mock
	private OfficeHoursMailService mailService;

	private OfficeHoursBookingService service;

	@BeforeEach
	void setUp() {
		service = new OfficeHoursBookingService(enrollmentRepository, userRepository, requestRepository,
				messageRepository, meetingRepository, settingsRepository, slotService, courseRepository, mailService,
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
		verify(mailService).requestChanged(any(), eq(Event.NEW_REQUEST), isNull());
		// The Moodle path takes the academic's lock too: one request to them at a time, whichever way it comes.
		verify(userRepository).findForUpdate(ACADEMIC);
	}

	@Test
	void anAcademicAtTheirStudentLimitTakesNoNewStudent() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
		when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(capacity(1)));
		OfficeHoursRequest someoneElse = new OfficeHoursRequest();
		someoneElse.setStudentId(UUID.randomUUID());
		when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of(someoneElse));
		when(meetingRepository.findByInstructorIdAndCancelledAtIsNullAndScheduledAtAfter(ACADEMIC, NOW))
				.thenReturn(List.of());

		assertThatThrownBy(() -> service.create(STUDENT, COURSE,
				new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM))))
				.isInstanceOf(ConflictException.class);
		verify(requestRepository, never()).saveAndFlush(any());
		verifyNoInteractions(mailService);
	}

	@Test
	void aStudentAlreadyWorkingWithTheAcademicCanStillAsk() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
		when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(capacity(1)));
		when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of());
		OfficeHoursMeeting own = new OfficeHoursMeeting();
		own.setStudentId(STUDENT);
		when(meetingRepository.findByInstructorIdAndCancelledAtIsNullAndScheduledAtAfter(ACADEMIC, NOW))
				.thenReturn(List.of(own));

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
		verify(mailService).requestChanged(any(), eq(Event.ACCEPTED), eq("Görüşürüz"));
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
		verify(mailService).requestChanged(request, Event.DECLINED, "Bu hafta yokum");
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
		verify(mailService).requestChanged(request, Event.MORE_INFO_REQUESTED, "Hangi soru?");
		verify(mailService).requestChanged(request, Event.REPLIED, "3. soru");
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
		verify(mailService).requestChanged(request, Event.COUNTER_PROPOSED, "Şunlar uygun");
		verify(mailService).requestChanged(request, Event.SLOT_CHOSEN, null);
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
		verify(mailService).requestChanged(request, Event.WITHDRAWN, null);

		assertThatThrownBy(() -> service.withdraw(STUDENT, COURSE, REQUEST))
				.isInstanceOf(InvalidRequestException.class);
	}

	@Test
	void theStepsMentonTakesWithNoCourseEMailTheOtherSideToo() {
		OfficeHoursRequest created = service.createRequest(STUDENT, null, new CreateOfficeHoursRequest(
				ACADEMIC, "Ödev 2", null, List.of(TUESDAY_2PM)));
		verify(mailService).requestChanged(created, Event.NEW_REQUEST, null);

		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);
		request.setCourseId(null);
		service.decide(request, ACADEMIC,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, "Bu hafta yokum", null, null));
		verify(mailService).requestChanged(request, Event.DECLINED, "Bu hafta yokum");
	}

	@Test
	void someoneElseCannotWithdrawOrAnswer() {
		existing(OfficeHoursRequestStatus.MORE_INFO_REQUESTED);

		assertThatThrownBy(() -> service.withdraw(ACADEMIC, COURSE, REQUEST))
				.isInstanceOf(ResponseStatusException.class);
		assertThatThrownBy(() -> service.reply(ACADEMIC, COURSE, REQUEST, new ReplyOfficeHoursRequest("x")))
				.isInstanceOf(ResponseStatusException.class);
	}

	private OfficeHoursMeeting acceptedMeetingAt(Instant at) {
		existing(OfficeHoursRequestStatus.ACCEPTED);
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setRequestId(REQUEST);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(20);
		lenient().when(meetingRepository.findByRequestId(REQUEST)).thenReturn(Optional.of(meeting));
		lenient().when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of(meeting));
		lenient().when(meetingRepository.cancelIfLive(any(), any(), any(), any())).thenReturn(1);
		return meeting;
	}

	@Test
	void whenBothSidesCancelAtOnceOnlyOneWinsAndTellsTheOther() {
		acceptedMeetingAt(NOW.plus(Duration.ofDays(2)));
		when(meetingRepository.cancelIfLive(any(), any(), any(), any())).thenReturn(0);

		assertThatThrownBy(() -> service.cancel(STUDENT, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(null)))
				.isInstanceOf(ConflictException.class);
		verifyNoInteractions(mailService);
	}

	@Test
	void eitherSideCancelsAnUpcomingMeetingAndTheOtherSideIsTold() {
		OfficeHoursMeeting meeting = acceptedMeetingAt(NOW.plusSeconds(3600));

		OfficeHoursRequestResponse response = service.cancel(STUDENT, COURSE, REQUEST,
				new CancelOfficeHoursMeetingRequest("  Hastayım "));

		assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.CANCELLED);
		assertThat(meeting.getCancelledAt()).isEqualTo(NOW);
		assertThat(meeting.getCancelledBy()).isEqualTo(STUDENT);
		assertThat(response.cancellation().reason()).isEqualTo("Hastayım");
		verify(mailService).requestChanged(any(), eq(Event.CANCELLED_BY_STUDENT), eq("Hastayım"));

		acceptedMeetingAt(NOW.plus(Duration.ofDays(2)));
		service.cancel(ACADEMIC, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(null));
		verify(mailService).requestChanged(any(), eq(Event.CANCELLED_BY_INSTRUCTOR), isNull());
	}

	@Test
	void anAcademicCancellingWithin24HoursMustSayWhyAndTheCancelIsMarkedLate() {
		acceptedMeetingAt(NOW.plus(Duration.ofHours(23)));
		assertThatThrownBy(() -> service.cancel(ACADEMIC, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(" ")))
				.isInstanceOf(InvalidRequestException.class);

		OfficeHoursRequestResponse late = service.cancel(ACADEMIC, COURSE, REQUEST,
				new CancelOfficeHoursMeetingRequest("Toplantıya çağrıldım"));
		assertThat(late.cancellation().late()).isTrue();

		// The student needs no reason, even late; the cancel is still marked late.
		acceptedMeetingAt(NOW.plus(Duration.ofHours(2)));
		assertThat(service.cancel(STUDENT, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(null))
				.cancellation().late()).isTrue();

		acceptedMeetingAt(NOW.plus(Duration.ofHours(25)));
		assertThat(service.cancel(ACADEMIC, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(null))
				.cancellation().late()).isFalse();
	}

	@Test
	void aMeetingThatStartedOrWasNeverAcceptedCannotBeCancelledNorBySomeoneElse() {
		// The meeting's state rules it out, not the request: a conflict (409), not a bad request.
		acceptedMeetingAt(NOW.minusSeconds(60));
		assertThatThrownBy(() -> service.cancel(STUDENT, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(null)))
				.isInstanceOf(ConflictException.class);
		assertThatThrownBy(() -> service.cancel(UUID.randomUUID(), COURSE, REQUEST,
				new CancelOfficeHoursMeetingRequest(null))).isInstanceOf(ResponseStatusException.class);

		existing(OfficeHoursRequestStatus.PENDING);
		assertThatThrownBy(() -> service.cancel(STUDENT, COURSE, REQUEST, new CancelOfficeHoursMeetingRequest(null)))
				.isInstanceOf(ConflictException.class);
		verifyNoInteractions(mailService);
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
		when(slotService.calendarSlots(ACADEMIC)).thenReturn(
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
		assertThat(response.allowedActions()).containsExactly(OfficeHoursMeetingAction.CANCEL);
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

	private static OfficeHoursUser person(UUID id, String fullName) {
		OfficeHoursUser user = new OfficeHoursUser();
		user.setId(id);
		user.setFullName(fullName);
		return user;
	}

	private static OfficeHoursEnrollment academicEnrollment(UUID userId) {
		OfficeHoursEnrollment enrollment = new OfficeHoursEnrollment();
		enrollment.setUserId(userId);
		enrollment.setCourseId(COURSE);
		enrollment.setRole(OfficeHoursRole.INSTRUCTOR);
		return enrollment;
	}

	private static OfficeHoursMeeting meetingAt(UUID requestId, Instant at) {
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setId(UUID.randomUUID());
		meeting.setRequestId(requestId);
		meeting.setCourseId(COURSE);
		meeting.setStudentId(STUDENT);
		meeting.setInstructorId(ACADEMIC);
		meeting.setScheduledAt(at);
		meeting.setDurationMinutes(30);
		return meeting;
	}

	private void typedTimesLastFortyFiveMinutes() {
		when(slotService.openSlotAt(any(), any())).thenReturn(Optional.empty());
		when(slotService.defaultMeetingMinutes(ACADEMIC)).thenReturn(45);
	}

	@Test
	void anyoneInTheCourseSeesItsAcademicsInNameOrder() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		UUID secondAcademic = UUID.randomUUID();
		when(enrollmentRepository.findByCourseIdAndRole(COURSE, OfficeHoursRole.INSTRUCTOR))
				.thenReturn(List.of(academicEnrollment(ACADEMIC), academicEnrollment(secondAcademic)));
		when(userRepository.findAllById(List.of(ACADEMIC, secondAcademic)))
				.thenReturn(List.of(person(ACADEMIC, "zeynep Kaya"), person(secondAcademic, "Ali Vural")));

		assertThat(service.instructors(STUDENT, COURSE)).containsExactly(
				new OfficeHoursPersonResponse(secondAcademic, "Ali Vural"),
				new OfficeHoursPersonResponse(ACADEMIC, "zeynep Kaya"));
	}

	@Test
	void someoneOutsideTheCourseSeesNeitherItsAcademicsNorTheirTimes() {
		UUID outsider = UUID.randomUUID();

		assertThatThrownBy(() -> service.instructors(outsider, COURSE)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.slots(outsider, COURSE, ACADEMIC))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.requests(outsider, COURSE)).isInstanceOf(ResourceNotFoundException.class);
		verify(enrollmentRepository, never()).findByCourseIdAndRole(any(), any());
		verify(slotService, never()).openSlots(any());
	}

	@Test
	void aStudentSeesAnAcademicsOpenTimesWithTheirLength() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
		Instant at = Instant.parse("2026-11-10T11:00:00Z");
		when(slotService.openSlots(ACADEMIC)).thenReturn(List.of(new OfficeHoursSlotService.Slot(at, 20)));

		assertThat(service.slots(STUDENT, COURSE, ACADEMIC)).containsExactly(new OfficeHoursSlotResponse(at, 20));
	}

	@Test
	void timesAreOnlyShownForAnAcademicOfTheCourse() {
		UUID classmate = UUID.randomUUID();
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(classmate, OfficeHoursRole.STUDENT);

		assertThatThrownBy(() -> service.slots(STUDENT, COURSE, classmate))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(exception.getReason()).isEqualTo("That person is not an academic of this course.");
				});
		verify(slotService, never()).openSlots(any());
	}

	@Test
	void anAcademicCannotSendARequest() {
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);

		assertThatThrownBy(() -> service.create(ACADEMIC, COURSE,
				new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM))))
				.isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
					assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(exception.getReason()).isEqualTo("Only the course's students can request office hours.");
				});
		verify(requestRepository, never()).saveAndFlush(any());
	}

	// NOW is 09:00 in Turkey, so 13:00 the same day is exactly the four-hour lead.
	@Test
	void aTimeExactlyFourHoursAwayIsBookableAndOneMinuteSoonerIsNot() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);

		OfficeHoursRequestResponse response = service.create(STUDENT, COURSE,
				new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of("2026-11-09T13:00")));

		assertThat(response.preferredSlots()).containsExactly("2026-11-09T13:00");
		assertThatThrownBy(() -> service.create(STUDENT, COURSE,
				new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM, "2026-11-09T12:59"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Görüşme saati en erken 4 saat sonrası için seçilebilir.");
		verify(requestRepository).saveAndFlush(any());
	}

	@Test
	void aTimeThatIsNotADateAndAnHourIsRefused() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);

		assertThatThrownBy(() -> service.create(STUDENT, COURSE,
				new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM, "salı öğleden sonra"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("2026-11-24T14:00");
		verify(requestRepository, never()).saveAndFlush(any());
	}

	@Test
	void anAcademicBelowTheirStudentLimitTakesANewStudent() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
		when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(capacity(2)));
		OfficeHoursRequest someoneElse = new OfficeHoursRequest();
		someoneElse.setStudentId(UUID.randomUUID());
		when(requestRepository.findByInstructorIdAndStatusIn(eq(ACADEMIC), any())).thenReturn(List.of(someoneElse));
		when(meetingRepository.findByInstructorIdAndCancelledAtIsNullAndScheduledAtAfter(ACADEMIC, NOW))
				.thenReturn(List.of());

		OfficeHoursRequestResponse response = service.create(STUDENT, COURSE,
				new CreateOfficeHoursRequest(ACADEMIC, "Soru", null, List.of(TUESDAY_2PM)));

		assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.PENDING);
	}

	@Test
	void aRequestThatWasAlreadyAnsweredCannotBeDecidedAgain() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.ACCEPTED);

		for (OfficeHoursRequestStatus answered : List.of(OfficeHoursRequestStatus.ACCEPTED,
				OfficeHoursRequestStatus.DECLINED, OfficeHoursRequestStatus.COUNTER_PROPOSED,
				OfficeHoursRequestStatus.WITHDRAWN)) {
			request.setStatus(answered);
			assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
					new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, "Vazgeçtim", null, null)))
					.as(answered.name())
					.isInstanceOf(InvalidRequestException.class);
			assertThat(request.getStatus()).isEqualTo(answered);
		}
		verify(requestRepository, never()).saveAndFlush(any());
	}

	@Test
	void theAcademicCanStillDecideWhileTheirQuestionIsUnanswered() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.MORE_INFO_REQUESTED);

		service.decide(ACADEMIC, COURSE, REQUEST,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, null, null, null));

		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.DECLINED);
		assertThat(request.getRejectionReason()).isNull();
		assertThat(request.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void aQuestionNeedsItsText() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);

		assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.MORE_INFO_REQUESTED, "   ", null, null)))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.PENDING);
		verify(messageRepository, never()).save(any());
	}

	@Test
	void anAnswerMustBeOneOfTheFourDecisions() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);

		for (OfficeHoursRequestStatus notADecision : List.of(OfficeHoursRequestStatus.PENDING,
				OfficeHoursRequestStatus.WITHDRAWN)) {
			assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
					new DecideOfficeHoursRequest(notADecision, null, null, null)))
					.as(notADecision.name())
					.isInstanceOf(InvalidRequestException.class);
		}
		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.PENDING);
		verify(requestRepository, never()).saveAndFlush(any());
	}

	@Test
	void aProposalSentWithoutAListOfTimesIsRefused() {
		existing(OfficeHoursRequestStatus.PENDING);

		assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.COUNTER_PROPOSED, "Başka zaman", null, null)))
				.isInstanceOf(InvalidRequestException.class);
		verify(requestRepository, never()).saveAndFlush(any());
	}

	@Test
	void acceptingWithoutATimeIsRefusedWhenNoneOfTheStudentsTimesIsStillFree() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);
		typedTimesLastFortyFiveMinutes();
		when(slotService.clashesWithCalendar(eq(ACADEMIC), any(), eq(45))).thenReturn(true);

		assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, null, null, null)))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.PENDING);
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void aStudentsTimeThatHasComeTooCloseCanNoLongerBeAccepted() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);
		request.setPreferredSlots("2026-11-09T10:00");

		assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, null, "2026-11-09T10:00", null)))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessage("Görüşme saati en erken 4 saat sonrası için seçilebilir.");
		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.PENDING);
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void aTimeSomeoneElseBookedAMomentAgoIsReportedAsTaken() {
		existing(OfficeHoursRequestStatus.PENDING);
		typedTimesLastFortyFiveMinutes();
		when(meetingRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate slot"));

		assertThatThrownBy(() -> service.decide(ACADEMIC, COURSE, REQUEST,
				new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, null, TUESDAY_2PM, null)))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("just been booked");
		verify(requestRepository, never()).saveAndFlush(any());
	}

	@Test
	void onlyARequestWithProposedTimesCanTakeOne() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);
		request.setProposedSlots(TUESDAY_2PM);

		assertThatThrownBy(() -> service.acceptProposedSlot(STUDENT, COURSE, REQUEST,
				new AcceptOfficeHoursSlotRequest(TUESDAY_2PM))).isInstanceOf(InvalidRequestException.class);
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void aProposedTimeThatHasComeTooCloseCanNoLongerBeTaken() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.COUNTER_PROPOSED);
		request.setProposedSlots("2026-11-09T10:00," + TUESDAY_3PM);

		assertThatThrownBy(() -> service.acceptProposedSlot(STUDENT, COURSE, REQUEST,
				new AcceptOfficeHoursSlotRequest("2026-11-09T10:00"))).isInstanceOf(InvalidRequestException.class);
		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.COUNTER_PROPOSED);
		verify(meetingRepository, never()).saveAndFlush(any());
	}

	@Test
	void onlyARequestWithAQuestionCanBeAnswered() {
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.PENDING);

		assertThatThrownBy(() -> service.reply(STUDENT, COURSE, REQUEST, new ReplyOfficeHoursRequest("3. soru")))
				.isInstanceOf(InvalidRequestException.class);
		assertThat(request.getStatus()).isEqualTo(OfficeHoursRequestStatus.PENDING);
		verify(messageRepository, never()).save(any());
	}

	@Test
	void aRequestCarriesItsPeopleItsConversationAndTheMeetingItBecame() {
		enrolled(STUDENT, OfficeHoursRole.STUDENT);
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.ACCEPTED);
		when(requestRepository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(COURSE, STUDENT))
				.thenReturn(List.of(request));
		OfficeHoursRequestMessage question = new OfficeHoursRequestMessage();
		question.setId(UUID.randomUUID());
		question.setRequestId(REQUEST);
		question.setAuthorId(ACADEMIC);
		question.setType(OfficeHoursMessageType.INSTRUCTOR_QUESTION);
		question.setBody("Hangi soru?");
		question.setCreatedAt(NOW);
		when(messageRepository.findByRequestIdInOrderByCreatedAtAsc(List.of(REQUEST))).thenReturn(List.of(question));
		Instant at = Instant.parse("2026-11-10T11:00:00Z");
		when(meetingRepository.findByRequestIdIn(List.of(REQUEST))).thenReturn(List.of(meetingAt(REQUEST, at)));
		when(userRepository.findAllById(Set.of(STUDENT, ACADEMIC)))
				.thenReturn(List.of(person(STUDENT, "Ece Öğrenci"), person(ACADEMIC, "Dr. Zeynep Kaya")));

		OfficeHoursRequestResponse response = service.requests(STUDENT, COURSE).getFirst();

		assertThat(response.student()).isEqualTo(new OfficeHoursPersonResponse(STUDENT, "Ece Öğrenci"));
		assertThat(response.instructor()).isEqualTo(new OfficeHoursPersonResponse(ACADEMIC, "Dr. Zeynep Kaya"));
		assertThat(response.messages()).containsExactly(new OfficeHoursRequestResponse.Message(
				question.getId(), ACADEMIC, OfficeHoursMessageType.INSTRUCTOR_QUESTION, "Hangi soru?", NOW));
		assertThat(response.meeting()).isEqualTo(new OfficeHoursSlotResponse(at, 30));
		// The meeting is still ahead, so either side may still call it off.
		assertThat(response.allowedActions()).containsExactly(OfficeHoursRequestAction.CANCEL);
	}

	@Test
	void upcomingMeetingsLeaveOutThePastAndComeSoonestFirst() {
		enrolled(ACADEMIC, OfficeHoursRole.INSTRUCTOR);
		OfficeHoursRequest request = existing(OfficeHoursRequestStatus.ACCEPTED);
		when(requestRepository.findByCourseIdAndInstructorIdOrderByCreatedAtDesc(COURSE, ACADEMIC))
				.thenReturn(List.of(request));
		OfficeHoursMeeting lastWeek = meetingAt(REQUEST, NOW.minusSeconds(7 * 86_400));
		OfficeHoursMeeting startingNow = meetingAt(REQUEST, NOW);
		OfficeHoursMeeting thursday = meetingAt(REQUEST, Instant.parse("2026-11-12T08:00:00Z"));
		OfficeHoursMeeting tuesday = meetingAt(REQUEST, Instant.parse("2026-11-10T11:00:00Z"));
		when(meetingRepository.findByRequestIdIn(any())).thenReturn(List.of(lastWeek, thursday, startingNow, tuesday));

		List<OfficeHoursMeetingResponse> upcoming = service.upcomingMeetings(ACADEMIC, COURSE);

		assertThat(upcoming).extracting(OfficeHoursMeetingResponse::id)
				.containsExactly(tuesday.getId(), thursday.getId());
		assertThat(upcoming.getFirst().topic()).isEqualTo("Ödev 2");
	}

	@Test
	void storedTimesBecomeRangesOfTheMeetingsLengthAndUnreadableOnesAreLeftOut() {
		when(slotService.calendarSlots(ACADEMIC)).thenReturn(
				List.of(new OfficeHoursSlotService.Slot(Instant.parse("2026-11-10T11:00:00Z"), 20)));
		when(slotService.defaultMeetingMinutes(ACADEMIC)).thenReturn(45);

		assertThat(service.timesOf(ACADEMIC, " " + TUESDAY_2PM + " , ,yarın," + TUESDAY_3PM)).containsExactly(
				new OfficeHoursTimeResponse(OffsetDateTime.parse("2026-11-10T14:00+03:00"),
						OffsetDateTime.parse("2026-11-10T14:20+03:00")),
				new OfficeHoursTimeResponse(OffsetDateTime.parse("2026-11-10T15:00+03:00"),
						OffsetDateTime.parse("2026-11-10T15:45+03:00")));
		assertThat(service.timesOf(ACADEMIC, null)).isEmpty();
		assertThat(service.timesOf(ACADEMIC, "  ")).isEmpty();
	}
}
