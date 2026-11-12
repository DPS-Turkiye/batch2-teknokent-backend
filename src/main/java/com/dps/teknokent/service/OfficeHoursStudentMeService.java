package com.dps.teknokent.service;

import java.text.Collator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.IdentityKind;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.AcceptOfficeHoursSlotRequest;
import com.dps.teknokent.dto.requests.AnswerOfficeHoursAlternativeRequest;
import com.dps.teknokent.dto.requests.CreateMentonOfficeHoursRequest;
import com.dps.teknokent.dto.requests.CreateOfficeHoursRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAcademicianResponse;
import com.dps.teknokent.dto.responses.OfficeHoursStudentRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursSettings;
import com.dps.teknokent.entity.OfficeHoursUser;
import com.dps.teknokent.exception.ConflictException;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.OfficeHoursCourseRepository;
import com.dps.teknokent.repository.OfficeHoursMeetingRepository;
import com.dps.teknokent.repository.OfficeHoursRequestRepository;
import com.dps.teknokent.repository.OfficeHoursSettingsRepository;
import com.dps.teknokent.repository.OfficeHoursUserRepository;
import com.dps.teknokent.util.MeetingSlotTimes;

import lombok.RequiredArgsConstructor;

/**
 * The student side of office hours on Menton: the academicians who chose to be listed, their open times, and the
 * student's own requests. Where to meet and the online link come with a request only once it is a meeting. ODTÜ's record must say the account is a student; otherwise everything is a 404. A request
 * goes to a listed academician for one of their open slots, through the same booking rules as the Moodle routes.
 */
@Service
@RequiredArgsConstructor
public class OfficeHoursStudentMeService {

	private static final Collator TURKISH = Collator.getInstance(Locale.forLanguageTag("tr"));
	/** How many open requests a student may have with one academician at a time. */
	static final int MAX_OPEN_PER_ACADEMICIAN = 3;

	private static final Set<OfficeHoursRequestStatus> OPEN = Set.of(OfficeHoursRequestStatus.PENDING,
			OfficeHoursRequestStatus.MORE_INFO_REQUESTED, OfficeHoursRequestStatus.COUNTER_PROPOSED);

	private final OfficeHoursPeople people;
	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursSettingsRepository settingsRepository;
	private final OfficeHoursRequestRepository requestRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursCourseRepository courseRepository;
	private final OfficeHoursBookingService bookingService;
	private final OfficeHoursSlotService slotService;

	/** The listed academicians, by name; never the student themselves. */
	@Transactional(readOnly = true)
	public List<OfficeHoursAcademicianResponse> academicians(UUID accountId) {
		people.require(accountId, IdentityKind.STUDENT);
		return listed(settingsRepository.findByListedTrue()).values().stream()
				.filter(academician -> !accountId.equals(academician.accountId()))
				.map(Listed::response)
				.sorted(Comparator.comparing(OfficeHoursAcademicianResponse::fullName, TURKISH))
				.toList();
	}

	@Transactional(readOnly = true)
	public OfficeHoursAcademicianResponse academician(UUID accountId, UUID academicianId) {
		people.require(accountId, IdentityKind.STUDENT);
		return listedAcademician(accountId, academicianId).response();
	}

	/** The academician's open times from now on, each as long as their slot. */
	@Transactional(readOnly = true)
	public List<OfficeHoursTimeResponse> slots(UUID accountId, UUID academicianId) {
		people.require(accountId, IdentityKind.STUDENT);
		listedAcademician(accountId, academicianId);
		return slotService.openSlots(academicianId).stream()
				.map(slot -> OfficeHoursTimeResponse.of(slot.startsAt(), slot.durationMinutes()))
				.toList();
	}

	/**
	 * Asks a listed academician for one of their open times. The student's office-hours person is made on the first
	 * request; asking twice for the same time while the first is still open is refused.
	 */
	@Transactional
	public OfficeHoursStudentRequestResponse request(UUID accountId, CreateMentonOfficeHoursRequest submission) {
		InstitutionPerson record = people.require(accountId, IdentityKind.STUDENT);
		UUID instructorId = listedAcademician(accountId, submission.academicianId()).person().getId();
		if (slotService.openSlotAt(instructorId, submission.startsAt()).isEmpty()) {
			throw new ConflictException("That time is no longer free. Please choose another one.");
		}
		String slot = MeetingSlotTimes.formatMachineSlot(submission.startsAt());
		UUID studentId = people.personFor(accountId, record).getId();
		userRepository.findForUpdate(studentId);
		List<OfficeHoursRequest> openWithThem = requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(studentId)
				.stream()
				.filter(open -> OPEN.contains(open.getStatus()) && open.getInstructorId().equals(instructorId))
				.toList();
		if (openWithThem.stream().anyMatch(open -> slots(open.getPreferredSlots()).contains(slot))) {
			throw new ConflictException("You have already asked for this time.");
		}
		if (openWithThem.size() >= MAX_OPEN_PER_ACADEMICIAN) {
			throw new ConflictException("You already have " + MAX_OPEN_PER_ACADEMICIAN
					+ " open requests with this academician. Wait for an answer first.");
		}
		OfficeHoursRequest created = bookingService.createRequest(studentId, null,
				new CreateOfficeHoursRequest(instructorId, submission.title(), submission.description(), List.of(slot)));
		return responses(List.of(created)).getFirst();
	}

	/** The student's own requests, newest first, from Menton or any course. */
	@Transactional(readOnly = true)
	public List<OfficeHoursStudentRequestResponse> appointments(UUID accountId) {
		people.require(accountId, IdentityKind.STUDENT);
		return people.existing(accountId)
				.map(person -> responses(requestRepository.findTop200ByStudentIdOrderByCreatedAtDesc(person.getId())))
				.orElse(List.of());
	}

	/** Takes one of the academician's other times, or withdraws the request instead. */
	@Transactional
	public OfficeHoursStudentRequestResponse answerAlternative(UUID accountId, UUID requestId,
			AnswerOfficeHoursAlternativeRequest answer) {
		people.require(accountId, IdentityKind.STUDENT);
		UUID studentId = people.existing(accountId).map(OfficeHoursUser::getId)
				.orElseThrow(OfficeHoursStudentMeService::requestNotFound);
		OfficeHoursRequest request = requestRepository.findForUpdate(requestId)
				.filter(found -> found.getStudentId().equals(studentId))
				.orElseThrow(OfficeHoursStudentMeService::requestNotFound);
		if (!answer.accept()) {
			return responses(List.of(bookingService.withdraw(request))).getFirst();
		}
		if (answer.time() == null) {
			throw new InvalidRequestException("Pick one of the times the academician offered");
		}
		return responses(List.of(bookingService.acceptProposedSlot(request,
				new AcceptOfficeHoursSlotRequest(MeetingSlotTimes.formatMachineSlot(answer.time()))))).getFirst();
	}

	/** An academician the student may see: listed, with an active ODTÜ academic record, and not the student. */
	private Listed listedAcademician(UUID accountId, UUID academicianId) {
		return settingsRepository.findById(academicianId)
				.filter(OfficeHoursSettings::isListed)
				.map(settings -> listed(List.of(settings)).get(academicianId))
				.filter(academician -> !accountId.equals(academician.accountId()))
				.orElseThrow(() -> new ResourceNotFoundException("Academician not found."));
	}

	private Map<UUID, Listed> listed(Collection<OfficeHoursSettings> settings) {
		if (settings.isEmpty()) {
			return Map.of();
		}
		Map<UUID, OfficeHoursSettings> byInstructor = settings.stream()
				.collect(Collectors.toMap(OfficeHoursSettings::getInstructorId, Function.identity()));
		List<OfficeHoursUser> persons = userRepository.findAllById(byInstructor.keySet()).stream()
				.filter(person -> person.getAppUserId() != null)
				.toList();
		Map<UUID, InstitutionPerson> records = people
				.recordsOf(persons.stream().map(OfficeHoursUser::getAppUserId).toList());
		return persons.stream()
				.filter(person -> {
					InstitutionPerson record = records.get(person.getAppUserId());
					return record != null && record.isActive() && record.isAcademic();
				})
				.collect(Collectors.toMap(OfficeHoursUser::getId, person -> new Listed(person,
						records.get(person.getAppUserId()), byInstructor.get(person.getId()))));
	}

	private List<OfficeHoursStudentRequestResponse> responses(List<OfficeHoursRequest> requests) {
		if (requests.isEmpty()) {
			return List.of();
		}
		Map<UUID, OfficeHoursMeeting> meetings = meetingRepository
				.findByRequestIdIn(requests.stream().map(OfficeHoursRequest::getId).toList()).stream()
				.collect(Collectors.toMap(OfficeHoursMeeting::getRequestId, Function.identity()));
		Set<UUID> instructorIds = requests.stream().map(OfficeHoursRequest::getInstructorId)
				.collect(Collectors.toSet());
		Map<UUID, OfficeHoursUser> instructors = userRepository.findAllById(instructorIds).stream()
				.collect(Collectors.toMap(OfficeHoursUser::getId, Function.identity()));
		Map<UUID, InstitutionPerson> records = people
				.recordsOf(instructors.values().stream().map(OfficeHoursUser::getAppUserId).toList());
		Map<UUID, OfficeHoursSettings> settings = settingsRepository.findAllById(instructorIds).stream()
				.collect(Collectors.toMap(OfficeHoursSettings::getInstructorId, Function.identity()));
		List<UUID> courseIds = requests.stream().map(OfficeHoursRequest::getCourseId).filter(Objects::nonNull)
				.distinct().toList();
		Map<UUID, String> courses = courseIds.isEmpty() ? Map.of()
				: courseRepository.findAllById(courseIds).stream()
						.collect(Collectors.toMap(OfficeHoursCourse::getId, OfficeHoursCourse::getTitle));
		return requests.stream().map(request -> {
			OfficeHoursUser instructor = instructors.get(request.getInstructorId());
			InstitutionPerson record = instructor == null || instructor.getAppUserId() == null ? null
					: records.get(instructor.getAppUserId());
			OfficeHoursSettings where = settings.getOrDefault(request.getInstructorId(), new OfficeHoursSettings());
			int length = where.getDefaultMeetingDurationMinutes();
			OfficeHoursMeeting meeting = meetings.get(request.getId());
			OfficeHoursTimeResponse time = meeting != null
					? OfficeHoursTimeResponse.of(meeting.getScheduledAt(), meeting.getDurationMinutes())
					: times(request.getPreferredSlots(), length).stream().findFirst().orElse(null);
			return new OfficeHoursStudentRequestResponse(request.getId(), request.getStatus(),
					new OfficeHoursStudentRequestResponse.Academician(request.getInstructorId(),
							record != null ? record.getFullName() : instructor == null ? null : instructor.getFullName(),
							record == null ? null : record.getAcademicTitle(),
							record == null ? null : record.getDepartmentName()),
					request.getTopic(), request.getExpectedOutcome(),
					request.getCourseId() == null ? null : courses.get(request.getCourseId()), time,
					times(request.getProposedSlots(), length), request.getInstructorMessage(),
					request.getRejectionReason(), meeting == null ? null : where.getDefaultPlace(),
					meeting == null ? null : where.getOnlineLink(),
					request.getCreatedAt(), OfficeHoursRequestAction.allowedFor(OfficeHoursRole.STUDENT,
							request.getStatus()));
		}).toList();
	}

	private static List<String> slots(String joined) {
		return joined == null || joined.isBlank() ? List.of()
				: Arrays.stream(joined.split(",")).map(String::trim).filter(slot -> !slot.isEmpty()).toList();
	}

	private static List<OfficeHoursTimeResponse> times(String joined, int minutes) {
		return slots(joined).stream()
				.map(MeetingSlotTimes::parseMachineSlot)
				.filter(Objects::nonNull)
				.map(startsAt -> OfficeHoursTimeResponse.of(startsAt, minutes))
				.toList();
	}

	private static ResourceNotFoundException requestNotFound() {
		return new ResourceNotFoundException("Request not found.");
	}

	/** A listed academician with what ODTÜ's record and their own settings say. */
	private record Listed(OfficeHoursUser person, InstitutionPerson record, OfficeHoursSettings settings) {

		UUID accountId() {
			return person.getAppUserId();
		}

		OfficeHoursAcademicianResponse response() {
			return new OfficeHoursAcademicianResponse(person.getId(), record.getFullName(), record.getAcademicTitle(),
					record.getDepartmentName(), record.getFaculty(), settings.getDefaultPlace(),
					settings.getOnlineLink(), settings.getContactEmail(), settings.getDefaultMeetingDurationMinutes());
		}
	}
}
