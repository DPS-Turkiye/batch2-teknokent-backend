package com.dps.teknokent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.dto.requests.AcceptOfficeHoursSlotRequest;
import com.dps.teknokent.dto.requests.AnswerOfficeHoursAlternativeRequest;
import com.dps.teknokent.dto.requests.CreateMentonOfficeHoursRequest;
import com.dps.teknokent.dto.requests.CreateOfficeHoursRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAcademicianResponse;
import com.dps.teknokent.dto.responses.OfficeHoursStudentRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.InstitutionPersonRepository;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursSettingsRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;

@ExtendWith(MockitoExtension.class)
class OfficeHoursStudentMeServiceTest {

	private static final UUID STUDENT_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000d001");
	private static final UUID STUDENT = UUID.fromString("00000000-0000-0000-0000-00000000d002");
	private static final UUID ACADEMIC_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000d003");
	private static final UUID ACADEMIC = UUID.fromString("00000000-0000-0000-0000-00000000d004");
	private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-00000000d005");
	/** Monday 9 November 2026, 09:00 in Turkey. */
	private static final Instant NOW = Instant.parse("2026-11-09T06:00:00Z");
	/** Tuesday 10 November 2026, 14:00 in Turkey. */
	private static final Instant TUESDAY_2PM = Instant.parse("2026-11-10T11:00:00Z");
	private static final String TUESDAY_2PM_SLOT = "2026-11-10T14:00";
	private static final String TUESDAY_3PM_SLOT = "2026-11-10T15:00";

	@Mock
	private InstitutionPersonRepository institutionPeople;
	@Mock
	private OfficeHoursUserRepository userRepository;
	@Mock
	private OfficeHoursSettingsRepository settingsRepository;
	@Mock
	private OfficeHoursRequestRepository requestRepository;
	@Mock
	private OfficeHoursMeetingRepository meetingRepository;
	@Mock
	private OfficeHoursCourseRepository courseRepository;
	@Mock
	private OfficeHoursBookingService bookingService;
	@Mock
	private OfficeHoursSlotService slotService;

	private OfficeHoursStudentMeService service;

	@BeforeEach
	void setUp() {
		service = new OfficeHoursStudentMeService(
				new OfficeHoursPeople(institutionPeople, userRepository, Clock.fixed(NOW, ZoneOffset.UTC)),
				userRepository, settingsRepository, requestRepository, meetingRepository, courseRepository,
				bookingService, slotService);
	}

	private static InstitutionPerson record(UUID accountId, String name, IdentityKind... kinds) {
		AppUser account = new AppUser();
		account.setId(accountId);
		InstitutionPerson person = new InstitutionPerson("odtu", "pid-" + accountId);
		person.setUser(account);
		person.setKinds(Set.of(kinds));
		person.setFullName(name);
		person.setAcademicTitle("Doç. Dr.");
		person.setDepartmentName("Bilgisayar Mühendisliği");
		person.setFaculty("Mühendislik Fakültesi");
		person.setSyncedAt(NOW);
		return person;
	}

	private static OfficeHoursUser person(UUID id, UUID accountId, String name) {
		OfficeHoursUser person = new OfficeHoursUser();
		person.setId(id);
		person.setAppUserId(accountId);
		person.setFullName(name);
		return person;
	}

	private static OfficeHoursSettings listedSettings(UUID instructorId) {
		OfficeHoursSettings settings = new OfficeHoursSettings();
		settings.setInstructorId(instructorId);
		settings.setListed(true);
		settings.setDefaultMeetingDurationMinutes(30);
		settings.setContactEmail("elif@example.edu");
		settings.setDefaultPlace("A-204");
		settings.setOnlineLink("https://meet.example.com/elif");
		return settings;
	}

	private static OfficeHoursRequest request(UUID instructorId, OfficeHoursRequestStatus status, String preferred) {
		OfficeHoursRequest request = new OfficeHoursRequest();
		request.setId(UUID.randomUUID());
		request.setStudentId(STUDENT);
		request.setInstructorId(instructorId);
		request.setStatus(status);
		request.setTopic("Staj");
		request.setExpectedOutcome("CV'ye bakalım");
		request.setPreferredSlots(preferred);
		request.setCreatedAt(NOW);
		return request;
	}

	private void student() {
		when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(STUDENT_ACCOUNT))
				.thenReturn(Optional.of(record(STUDENT_ACCOUNT, "Ali Kaya", IdentityKind.STUDENT)));
	}

	private void studentHasPerson() {
		when(userRepository.findByAppUserId(STUDENT_ACCOUNT))
				.thenReturn(Optional.of(person(STUDENT, STUDENT_ACCOUNT, "Ali Kaya")));
	}

	/** The academician's stored name is stale on purpose: ODTÜ's record is the one shown. */
	private void academicianIsKnown() {
		when(userRepository.findAllById(Set.of(ACADEMIC)))
				.thenReturn(List.of(person(ACADEMIC, ACADEMIC_ACCOUNT, "Eski Ad")));
		when(institutionPeople.findByUserIdIn(List.of(ACADEMIC_ACCOUNT)))
				.thenReturn(List.of(record(ACADEMIC_ACCOUNT, "Elif Şahin", IdentityKind.ACADEMIC)));
	}

	private void academicianIsListed() {
		when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(listedSettings(ACADEMIC)));
		academicianIsKnown();
	}

	private void theTimeIsOpen() {
		when(slotService.openSlotAt(ACADEMIC, TUESDAY_2PM))
				.thenReturn(Optional.of(new OfficeHoursSlotService.Slot(TUESDAY_2PM, 20)));
	}

	private void storedTimesAreTwentyMinuteRanges() {
		when(bookingService.timesOf(any(UUID.class), any())).thenAnswer(
				invocation -> OfficeHoursBookingService.timesOf(invocation.getArgument(1), startsAt -> 20));
	}

	private static CreateMentonOfficeHoursRequest askForTuesday() {
		return new CreateMentonOfficeHoursRequest(ACADEMIC, TUESDAY_2PM, "Staj", "CV'ye bakalım");
	}

	private static OffsetDateTime turkeyTime(String localTime) {
		return OffsetDateTime.parse(localTime + ":00+03:00");
	}

	@Test
	void isNotThereForSomeoneOdtuDoesNotCallAStudent() {
		when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(STUDENT_ACCOUNT))
				.thenReturn(Optional.of(record(STUDENT_ACCOUNT, "Elif Şahin", IdentityKind.ACADEMIC)));

		assertThatThrownBy(() -> service.academicians(STUDENT_ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.academician(STUDENT_ACCOUNT, ACADEMIC))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.slots(STUDENT_ACCOUNT, ACADEMIC))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.request(STUDENT_ACCOUNT, askForTuesday()))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.appointments(STUDENT_ACCOUNT)).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.answerAlternative(STUDENT_ACCOUNT, UUID.randomUUID(),
				new AnswerOfficeHoursAlternativeRequest(false, null))).isInstanceOf(ResourceNotFoundException.class);
		verifyNoInteractions(settingsRepository, requestRepository, bookingService, slotService);
	}

	@Test
	void listsTheListedAcademiciansInTurkishNameOrder() {
		student();
		UUID cagla = UUID.randomUUID();
		UUID caglaAccount = UUID.randomUUID();
		UUID ipek = UUID.randomUUID();
		UUID ipekAccount = UUID.randomUUID();
		UUID zeynep = UUID.randomUUID();
		UUID zeynepAccount = UUID.randomUUID();
		when(settingsRepository.findByListedTrue()).thenReturn(List.of(listedSettings(zeynep),
				listedSettings(ACADEMIC), listedSettings(ipek), listedSettings(cagla)));
		when(userRepository.findAllById(Set.of(zeynep, ACADEMIC, ipek, cagla))).thenReturn(List.of(
				person(zeynep, zeynepAccount, "Zeynep Ak"), person(ACADEMIC, ACADEMIC_ACCOUNT, "Elif Şahin"),
				person(ipek, ipekAccount, "İpek Yıldız"), person(cagla, caglaAccount, "Çağla Demir")));
		when(institutionPeople.findByUserIdIn(any())).thenReturn(List.of(
				record(zeynepAccount, "Zeynep Ak", IdentityKind.ACADEMIC),
				record(ACADEMIC_ACCOUNT, "Elif Şahin", IdentityKind.ACADEMIC),
				record(ipekAccount, "İpek Yıldız", IdentityKind.ACADEMIC),
				record(caglaAccount, "Çağla Demir", IdentityKind.ACADEMIC)));

		assertThat(service.academicians(STUDENT_ACCOUNT)).extracting(OfficeHoursAcademicianResponse::fullName)
				.containsExactly("Çağla Demir", "Elif Şahin", "İpek Yıldız", "Zeynep Ak");
	}

	@Test
	void leavesOutAnyoneWhoIsNotAnActiveOdtuAcademicWithAnAccountAndTheStudentThemselves() {
		InstitutionPerson studentWhoAlsoTeaches = record(STUDENT_ACCOUNT, "Ali Kaya", IdentityKind.STUDENT,
				IdentityKind.ACADEMIC);
		when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(STUDENT_ACCOUNT))
				.thenReturn(Optional.of(studentWhoAlsoTeaches));
		UUID moodleOnly = UUID.randomUUID();
		UUID left = UUID.randomUUID();
		UUID leftAccount = UUID.randomUUID();
		UUID noLongerAcademic = UUID.randomUUID();
		UUID noLongerAcademicAccount = UUID.randomUUID();
		UUID withoutRecord = UUID.randomUUID();
		InstitutionPerson leftOdtu = record(leftAccount, "Ayrılan Hoca", IdentityKind.ACADEMIC);
		leftOdtu.setActive(false);
		when(settingsRepository.findByListedTrue()).thenReturn(List.of(listedSettings(ACADEMIC),
				listedSettings(moodleOnly), listedSettings(left), listedSettings(noLongerAcademic),
				listedSettings(withoutRecord), listedSettings(STUDENT)));
		when(userRepository.findAllById(any())).thenReturn(List.of(
				person(ACADEMIC, ACADEMIC_ACCOUNT, "Elif Şahin"), person(moodleOnly, null, "Moodle Hoca"),
				person(left, leftAccount, "Ayrılan Hoca"),
				person(noLongerAcademic, noLongerAcademicAccount, "Eski Hoca"),
				person(withoutRecord, UUID.randomUUID(), "Kayıtsız Hoca"),
				person(STUDENT, STUDENT_ACCOUNT, "Ali Kaya")));
		when(institutionPeople.findByUserIdIn(any())).thenReturn(List.of(
				record(ACADEMIC_ACCOUNT, "Elif Şahin", IdentityKind.ACADEMIC), leftOdtu,
				record(noLongerAcademicAccount, "Eski Hoca", IdentityKind.STUDENT), studentWhoAlsoTeaches));

		assertThat(service.academicians(STUDENT_ACCOUNT)).containsExactly(new OfficeHoursAcademicianResponse(
				ACADEMIC, ACADEMIC_ACCOUNT, "Elif Şahin", "Doç. Dr.", "Bilgisayar Mühendisliği",
				"Mühendislik Fakültesi", "elif@example.edu", 30));
	}

	@Test
	void whenNobodyIsListedTheListIsEmptyAndNobodyIsLookedUp() {
		student();
		when(settingsRepository.findByListedTrue()).thenReturn(List.of());

		assertThat(service.academicians(STUDENT_ACCOUNT)).isEmpty();
		verify(userRepository, never()).findAllById(any());
	}

	@Test
	void showsOneListedAcademicianUnderOdtusNameWithTheirOwnContactAndMeetingLength() {
		student();
		academicianIsListed();

		assertThat(service.academician(STUDENT_ACCOUNT, ACADEMIC)).isEqualTo(new OfficeHoursAcademicianResponse(
				ACADEMIC, ACADEMIC_ACCOUNT, "Elif Şahin", "Doç. Dr.", "Bilgisayar Mühendisliği",
				"Mühendislik Fakültesi", "elif@example.edu", 30));
	}

	@Test
	void anAcademicianWhoIsNotListedOrHasNoSettingsIsNotFound() {
		student();
		OfficeHoursSettings notListed = listedSettings(ACADEMIC);
		notListed.setListed(false);
		when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(notListed));
		UUID neverSavedAnything = UUID.randomUUID();

		assertThatThrownBy(() -> service.academician(STUDENT_ACCOUNT, ACADEMIC))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.slots(STUDENT_ACCOUNT, ACADEMIC))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.academician(STUDENT_ACCOUNT, neverSavedAnything))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(slotService, never()).openSlots(any());
	}

	@Test
	void aListedAcademicianOdtuNoLongerCallsAnAcademicIsNotFound() {
		student();
		when(settingsRepository.findById(ACADEMIC)).thenReturn(Optional.of(listedSettings(ACADEMIC)));
		when(userRepository.findAllById(Set.of(ACADEMIC)))
				.thenReturn(List.of(person(ACADEMIC, ACADEMIC_ACCOUNT, "Elif Şahin")));
		when(institutionPeople.findByUserIdIn(List.of(ACADEMIC_ACCOUNT)))
				.thenReturn(List.of(record(ACADEMIC_ACCOUNT, "Elif Şahin", IdentityKind.STAFF)));

		assertThatThrownBy(() -> service.request(STUDENT_ACCOUNT, askForTuesday()))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(bookingService, never()).createRequest(any(), any(), any());
	}

	@Test
	void aStudentWhoIsAlsoAListedAcademicCannotSeeOrBookThemselves() {
		InstitutionPerson studentWhoAlsoTeaches = record(STUDENT_ACCOUNT, "Ali Kaya", IdentityKind.STUDENT,
				IdentityKind.ACADEMIC);
		when(institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(STUDENT_ACCOUNT))
				.thenReturn(Optional.of(studentWhoAlsoTeaches));
		when(settingsRepository.findById(STUDENT)).thenReturn(Optional.of(listedSettings(STUDENT)));
		when(userRepository.findAllById(Set.of(STUDENT)))
				.thenReturn(List.of(person(STUDENT, STUDENT_ACCOUNT, "Ali Kaya")));
		when(institutionPeople.findByUserIdIn(List.of(STUDENT_ACCOUNT))).thenReturn(List.of(studentWhoAlsoTeaches));

		assertThatThrownBy(() -> service.academician(STUDENT_ACCOUNT, STUDENT))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.request(STUDENT_ACCOUNT,
				new CreateMentonOfficeHoursRequest(STUDENT, TUESDAY_2PM, "Staj", null)))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(bookingService, never()).createRequest(any(), any(), any());
	}

	@Test
	void showsAListedAcademiciansOpenTimesAsRangesWithTurkeysOffset() {
		student();
		academicianIsListed();
		when(slotService.openSlots(ACADEMIC)).thenReturn(List.of(new OfficeHoursSlotService.Slot(TUESDAY_2PM, 20)));

		assertThat(service.slots(STUDENT_ACCOUNT, ACADEMIC)).containsExactly(
				new OfficeHoursTimeResponse(turkeyTime("2026-11-10T14:00"), turkeyTime("2026-11-10T14:20")));
	}

	@Test
	void askingForAnOpenTimeGoesThroughTheBookingRulesWithoutACourse() {
		student();
		studentHasPerson();
		academicianIsListed();
		theTimeIsOpen();
		storedTimesAreTwentyMinuteRanges();
		when(settingsRepository.findAllById(Set.of(ACADEMIC))).thenReturn(List.of(listedSettings(ACADEMIC)));
		OfficeHoursRequest created = request(ACADEMIC, OfficeHoursRequestStatus.PENDING, TUESDAY_2PM_SLOT);
		when(bookingService.createRequest(STUDENT, null,
				new CreateOfficeHoursRequest(ACADEMIC, "Staj", "CV'ye bakalım", List.of(TUESDAY_2PM_SLOT))))
				.thenReturn(created);

		OfficeHoursStudentRequestResponse response = service.request(STUDENT_ACCOUNT, askForTuesday());

		assertThat(response.id()).isEqualTo(created.getId());
		assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.PENDING);
		assertThat(response.academician()).isEqualTo(new OfficeHoursStudentRequestResponse.Academician(
				ACADEMIC, "Elif Şahin", "Doç. Dr.", "Bilgisayar Mühendisliği"));
		assertThat(response.time()).isEqualTo(
				new OfficeHoursTimeResponse(turkeyTime("2026-11-10T14:00"), turkeyTime("2026-11-10T14:20")));
		assertThat(response.courseName()).isNull();
		// Where to meet is only told once the request is a meeting.
		assertThat(response.place()).isNull();
		assertThat(response.onlineLink()).isNull();
		assertThat(response.allowedActions()).containsExactly(OfficeHoursRequestAction.WITHDRAW);
		verify(userRepository).findForUpdate(ACADEMIC);
	}

	@Test
	void aTimeThatIsNoLongerOpenIsRefusedBeforeAnythingIsMade() {
		student();
		academicianIsListed();

		assertThatThrownBy(() -> service.request(STUDENT_ACCOUNT, askForTuesday()))
				.isInstanceOf(ConflictException.class);
		verify(bookingService, never()).createRequest(any(), any(), any());
		verify(userRepository, never()).insertForAccountIfAbsent(any(), any(), any(), any());
	}

	@Test
	void theSameTimeIsNotAskedForTwiceWhileTheFirstRequestIsOpen() {
		student();
		studentHasPerson();
		academicianIsListed();
		theTimeIsOpen();
		when(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(STUDENT)).thenReturn(List.of(
				request(ACADEMIC, OfficeHoursRequestStatus.MORE_INFO_REQUESTED,
						TUESDAY_3PM_SLOT + ", " + TUESDAY_2PM_SLOT)));

		assertThatThrownBy(() -> service.request(STUDENT_ACCOUNT, askForTuesday()))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("already asked");
		verify(bookingService, never()).createRequest(any(), any(), any());
	}

	@Test
	void aFourthOpenRequestWithTheSameAcademicianMustWaitForAnAnswer() {
		student();
		studentHasPerson();
		academicianIsListed();
		theTimeIsOpen();
		when(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(STUDENT)).thenReturn(List.of(
				request(ACADEMIC, OfficeHoursRequestStatus.PENDING, "2026-11-11T10:00"),
				request(ACADEMIC, OfficeHoursRequestStatus.MORE_INFO_REQUESTED, "2026-11-12T10:00"),
				request(ACADEMIC, OfficeHoursRequestStatus.COUNTER_PROPOSED, "2026-11-13T10:00")));

		assertThatThrownBy(() -> service.request(STUDENT_ACCOUNT, askForTuesday()))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("3 open requests");
		verify(bookingService, never()).createRequest(any(), any(), any());
	}

	@Test
	void closedRequestsAndOnesWithOtherAcademiciansNeitherBlockTheTimeNorCountTowardsTheThree() {
		student();
		studentHasPerson();
		academicianIsListed();
		theTimeIsOpen();
		storedTimesAreTwentyMinuteRanges();
		UUID anotherAcademician = UUID.randomUUID();
		when(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(STUDENT)).thenReturn(List.of(
				request(ACADEMIC, OfficeHoursRequestStatus.PENDING, "2026-11-11T10:00"),
				request(ACADEMIC, OfficeHoursRequestStatus.PENDING, "2026-11-12T10:00"),
				request(ACADEMIC, OfficeHoursRequestStatus.DECLINED, TUESDAY_2PM_SLOT),
				request(ACADEMIC, OfficeHoursRequestStatus.WITHDRAWN, TUESDAY_2PM_SLOT),
				request(ACADEMIC, OfficeHoursRequestStatus.ACCEPTED, "2026-11-13T10:00"),
				request(anotherAcademician, OfficeHoursRequestStatus.PENDING, TUESDAY_2PM_SLOT),
				request(anotherAcademician, OfficeHoursRequestStatus.PENDING, "2026-11-13T10:00")));
		OfficeHoursRequest created = request(ACADEMIC, OfficeHoursRequestStatus.PENDING, TUESDAY_2PM_SLOT);
		when(bookingService.createRequest(any(), any(), any())).thenReturn(created);

		assertThat(service.request(STUDENT_ACCOUNT, askForTuesday()).id()).isEqualTo(created.getId());
	}

	@Test
	void aStudentWhoNeverAskedHasNoAppointmentsAndLookingMakesNobody() {
		student();

		assertThat(service.appointments(STUDENT_ACCOUNT)).isEmpty();
		verify(requestRepository, never()).findTop200ByStudentIdOrderByCreatedAtDesc(any());
		verify(userRepository, never()).insertForAccountIfAbsent(any(), any(), any(), any());
	}

	@Test
	void aStudentWithNoRequestsLeftSeesAnEmptyList() {
		student();
		studentHasPerson();
		when(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(STUDENT)).thenReturn(List.of());

		assertThat(service.appointments(STUDENT_ACCOUNT)).isEmpty();
		verify(meetingRepository, never()).findByRequestIdIn(any());
	}

	@Test
	void anAcceptedRequestShowsItsMeetingTimeAndWhereToMeet() {
		student();
		studentHasPerson();
		academicianIsKnown();
		storedTimesAreTwentyMinuteRanges();
		OfficeHoursRequest accepted = request(ACADEMIC, OfficeHoursRequestStatus.ACCEPTED,
				TUESDAY_2PM_SLOT + "," + TUESDAY_3PM_SLOT);
		when(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(STUDENT)).thenReturn(List.of(accepted));
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setId(UUID.randomUUID());
		meeting.setRequestId(accepted.getId());
		meeting.setScheduledAt(TUESDAY_2PM.plusSeconds(3600));
		meeting.setDurationMinutes(45);
		when(meetingRepository.findByRequestIdIn(List.of(accepted.getId()))).thenReturn(List.of(meeting));
		when(settingsRepository.findAllById(Set.of(ACADEMIC))).thenReturn(List.of(listedSettings(ACADEMIC)));

		OfficeHoursStudentRequestResponse response = service.appointments(STUDENT_ACCOUNT).getFirst();

		assertThat(response.time()).isEqualTo(
				new OfficeHoursTimeResponse(turkeyTime("2026-11-10T15:00"), turkeyTime("2026-11-10T15:45")));
		assertThat(response.place()).isEqualTo("A-204");
		assertThat(response.onlineLink()).isEqualTo("https://meet.example.com/elif");
		assertThat(response.academician().fullName()).isEqualTo("Elif Şahin");
		assertThat(response.description()).isEqualTo("CV'ye bakalım");
		assertThat(response.allowedActions()).isEmpty();
	}

	@Test
	void aCourseRequestShowsItsCourseTheOfferedTimesAndAMoodleAcademicsNameWithoutATitle() {
		student();
		studentHasPerson();
		storedTimesAreTwentyMinuteRanges();
		UUID moodleAcademic = UUID.randomUUID();
		UUID removedAcademic = UUID.randomUUID();
		OfficeHoursRequest fromCourse = request(moodleAcademic, OfficeHoursRequestStatus.COUNTER_PROPOSED,
				TUESDAY_2PM_SLOT);
		fromCourse.setCourseId(COURSE);
		fromCourse.setProposedSlots(TUESDAY_3PM_SLOT);
		fromCourse.setInstructorMessage("Şu saat uygun");
		OfficeHoursRequest declined = request(removedAcademic, OfficeHoursRequestStatus.DECLINED, TUESDAY_2PM_SLOT);
		declined.setRejectionReason("Bu hafta yokum");
		when(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(STUDENT))
				.thenReturn(List.of(fromCourse, declined));
		when(userRepository.findAllById(Set.of(moodleAcademic, removedAcademic)))
				.thenReturn(List.of(person(moodleAcademic, null, "Dr. Moodle Hoca")));
		OfficeHoursCourse course = new OfficeHoursCourse();
		course.setId(COURSE);
		course.setTitle("CENG 242 Programlama Dilleri");
		when(courseRepository.findAllById(List.of(COURSE))).thenReturn(List.of(course));

		List<OfficeHoursStudentRequestResponse> appointments = service.appointments(STUDENT_ACCOUNT);

		OfficeHoursStudentRequestResponse offered = appointments.get(0);
		assertThat(offered.courseName()).isEqualTo("CENG 242 Programlama Dilleri");
		assertThat(offered.academician()).isEqualTo(
				new OfficeHoursStudentRequestResponse.Academician(moodleAcademic, "Dr. Moodle Hoca", null, null));
		assertThat(offered.proposedTimes()).containsExactly(
				new OfficeHoursTimeResponse(turkeyTime("2026-11-10T15:00"), turkeyTime("2026-11-10T15:20")));
		assertThat(offered.academicianMessage()).isEqualTo("Şu saat uygun");
		assertThat(offered.allowedActions()).containsExactly(OfficeHoursRequestAction.ACCEPT_PROPOSED,
				OfficeHoursRequestAction.WITHDRAW);
		OfficeHoursStudentRequestResponse refused = appointments.get(1);
		assertThat(refused.courseName()).isNull();
		assertThat(refused.academician()).isEqualTo(
				new OfficeHoursStudentRequestResponse.Academician(removedAcademic, null, null, null));
		assertThat(refused.rejectionReason()).isEqualTo("Bu hafta yokum");
		assertThat(refused.place()).isNull();
	}

	@Test
	void takingAnOfferedTimePassesItInTurkeyTimeToTheBookingRules() {
		student();
		studentHasPerson();
		academicianIsKnown();
		storedTimesAreTwentyMinuteRanges();
		OfficeHoursRequest offered = request(ACADEMIC, OfficeHoursRequestStatus.COUNTER_PROPOSED, TUESDAY_3PM_SLOT);
		offered.setProposedSlots(TUESDAY_2PM_SLOT);
		when(requestRepository.findForUpdate(offered.getId())).thenReturn(Optional.of(offered));
		when(bookingService.acceptProposedSlot(offered, new AcceptOfficeHoursSlotRequest(TUESDAY_2PM_SLOT)))
				.thenReturn(offered);

		OfficeHoursStudentRequestResponse response = service.answerAlternative(STUDENT_ACCOUNT, offered.getId(),
				new AnswerOfficeHoursAlternativeRequest(true, TUESDAY_2PM));

		assertThat(response.id()).isEqualTo(offered.getId());
		verify(bookingService).acceptProposedSlot(offered, new AcceptOfficeHoursSlotRequest(TUESDAY_2PM_SLOT));
		verify(bookingService, never()).withdraw(any(OfficeHoursRequest.class));
	}

	@Test
	void sayingNoToTheOfferedTimesWithdrawsTheRequest() {
		student();
		studentHasPerson();
		academicianIsKnown();
		storedTimesAreTwentyMinuteRanges();
		OfficeHoursRequest offered = request(ACADEMIC, OfficeHoursRequestStatus.COUNTER_PROPOSED, TUESDAY_3PM_SLOT);
		OfficeHoursRequest withdrawn = request(ACADEMIC, OfficeHoursRequestStatus.WITHDRAWN, TUESDAY_3PM_SLOT);
		when(requestRepository.findForUpdate(offered.getId())).thenReturn(Optional.of(offered));
		when(bookingService.withdraw(offered)).thenReturn(withdrawn);

		// A time sent along with a "no" is ignored.
		OfficeHoursStudentRequestResponse response = service.answerAlternative(STUDENT_ACCOUNT, offered.getId(),
				new AnswerOfficeHoursAlternativeRequest(false, TUESDAY_2PM));

		assertThat(response.status()).isEqualTo(OfficeHoursRequestStatus.WITHDRAWN);
		assertThat(response.allowedActions()).isEmpty();
		verify(bookingService, never()).acceptProposedSlot(any(OfficeHoursRequest.class), any());
	}

	@Test
	void takingAnOfferedTimeNeedsTheTime() {
		student();
		studentHasPerson();
		OfficeHoursRequest offered = request(ACADEMIC, OfficeHoursRequestStatus.COUNTER_PROPOSED, TUESDAY_3PM_SLOT);
		when(requestRepository.findForUpdate(offered.getId())).thenReturn(Optional.of(offered));

		assertThatThrownBy(() -> service.answerAlternative(STUDENT_ACCOUNT, offered.getId(),
				new AnswerOfficeHoursAlternativeRequest(true, null))).isInstanceOf(InvalidRequestException.class);
		verify(bookingService, never()).acceptProposedSlot(any(OfficeHoursRequest.class), any());
		verify(bookingService, never()).withdraw(any(OfficeHoursRequest.class));
	}

	@Test
	void anotherStudentsRequestAndAnUnknownOneAreNotFound() {
		student();
		studentHasPerson();
		OfficeHoursRequest someoneElses = request(ACADEMIC, OfficeHoursRequestStatus.COUNTER_PROPOSED,
				TUESDAY_3PM_SLOT);
		someoneElses.setStudentId(UUID.randomUUID());
		when(requestRepository.findForUpdate(someoneElses.getId())).thenReturn(Optional.of(someoneElses));
		UUID unknown = UUID.randomUUID();
		when(requestRepository.findForUpdate(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.answerAlternative(STUDENT_ACCOUNT, someoneElses.getId(),
				new AnswerOfficeHoursAlternativeRequest(false, null))).isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.answerAlternative(STUDENT_ACCOUNT, unknown,
				new AnswerOfficeHoursAlternativeRequest(false, null))).isInstanceOf(ResourceNotFoundException.class);
		verify(bookingService, never()).withdraw(any(OfficeHoursRequest.class));
	}

	@Test
	void aStudentWhoNeverAskedHasNoRequestToAnswer() {
		student();

		assertThatThrownBy(() -> service.answerAlternative(STUDENT_ACCOUNT, UUID.randomUUID(),
				new AnswerOfficeHoursAlternativeRequest(false, null))).isInstanceOf(ResourceNotFoundException.class);
		verify(requestRepository, never()).findForUpdate(any());
	}
}
