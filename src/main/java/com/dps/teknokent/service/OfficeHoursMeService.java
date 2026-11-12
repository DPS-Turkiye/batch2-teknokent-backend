package com.dps.teknokent.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.OfficeHoursMessageType;
import com.dps.teknokent.domain.OfficeHoursRequestAction;
import com.dps.teknokent.domain.OfficeHoursRequestStatus;
import com.dps.teknokent.domain.OfficeHoursRole;
import com.dps.teknokent.dto.requests.AnswerOfficeHoursRequest;
import com.dps.teknokent.dto.requests.DecideOfficeHoursRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursAvailabilityRequest;
import com.dps.teknokent.dto.requests.UpdateOfficeHoursProfileRequest;
import com.dps.teknokent.dto.responses.OfficeHoursAcademicianMeetingResponse;
import com.dps.teknokent.dto.responses.OfficeHoursAvailabilityResponse;
import com.dps.teknokent.dto.responses.OfficeHoursInboxRequestResponse;
import com.dps.teknokent.dto.responses.OfficeHoursProfileResponse;
import com.dps.teknokent.dto.responses.OfficeHoursStudentResponse;
import com.dps.teknokent.dto.responses.OfficeHoursTimeResponse;
import com.dps.teknokent.entity.InstitutionPerson;
import com.dps.teknokent.entity.OfficeHoursCourse;
import com.dps.teknokent.entity.OfficeHoursMeeting;
import com.dps.teknokent.entity.OfficeHoursRequest;
import com.dps.teknokent.entity.OfficeHoursRequestMessage;
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
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.TextUtils;

/**
 * Office hours for someone signed in to Menton through ODTÜ, with no Moodle launch needed. ODTÜ's record says who
 * may do what: an academic sets hours and answers requests, a student books. Their office-hours person is made the
 * first time they save something; until then every read is simply empty. Anyone without that record (e-mail
 * sign-ups, Teknokent accounts) gets a 404, as does anything that isn't theirs. The booking rules are the same
 * ones the Moodle routes use.
 */
@Service
public class OfficeHoursMeService {

	private static final Set<OfficeHoursRequestStatus> ANSWERABLE = Set.of(OfficeHoursRequestStatus.PENDING,
			OfficeHoursRequestStatus.MORE_INFO_REQUESTED);

	private final InstitutionPersonRepository institutionPeople;
	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursSettingsRepository settingsRepository;
	private final OfficeHoursRequestRepository requestRepository;
	private final OfficeHoursRequestMessageRepository messageRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursCourseRepository courseRepository;
	private final OfficeHoursAvailabilityService availabilityService;
	private final OfficeHoursBookingService bookingService;
	private final OfficeHoursSlotService slotService;
	private final Clock clock;

	@Autowired
	public OfficeHoursMeService(InstitutionPersonRepository institutionPeople, OfficeHoursUserRepository userRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursRequestRepository requestRepository,
			OfficeHoursRequestMessageRepository messageRepository, OfficeHoursMeetingRepository meetingRepository,
			OfficeHoursCourseRepository courseRepository, OfficeHoursAvailabilityService availabilityService,
			OfficeHoursBookingService bookingService, OfficeHoursSlotService slotService) {
		this(institutionPeople, userRepository, settingsRepository, requestRepository, messageRepository,
				meetingRepository, courseRepository, availabilityService, bookingService, slotService,
				Clock.systemUTC());
	}

	OfficeHoursMeService(InstitutionPersonRepository institutionPeople, OfficeHoursUserRepository userRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursRequestRepository requestRepository,
			OfficeHoursRequestMessageRepository messageRepository, OfficeHoursMeetingRepository meetingRepository,
			OfficeHoursCourseRepository courseRepository, OfficeHoursAvailabilityService availabilityService,
			OfficeHoursBookingService bookingService, OfficeHoursSlotService slotService, Clock clock) {
		this.institutionPeople = institutionPeople;
		this.userRepository = userRepository;
		this.settingsRepository = settingsRepository;
		this.requestRepository = requestRepository;
		this.messageRepository = messageRepository;
		this.meetingRepository = meetingRepository;
		this.courseRepository = courseRepository;
		this.availabilityService = availabilityService;
		this.bookingService = bookingService;
		this.slotService = slotService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public OfficeHoursAvailabilityResponse availability(UUID accountId) {
		requireAcademic(accountId);
		return existing(accountId).map(person -> availabilityService.read(person.getId()))
				.orElseGet(() -> availabilityOf(new OfficeHoursSettings()));
	}

	@Transactional
	public OfficeHoursAvailabilityResponse saveAvailability(UUID accountId,
			UpdateOfficeHoursAvailabilityRequest request) {
		InstitutionPerson record = requireAcademic(accountId);
		return availabilityService.write(personFor(accountId, record).getId(), request);
	}

	@Transactional(readOnly = true)
	public OfficeHoursProfileResponse profile(UUID accountId) {
		requireAcademic(accountId);
		return profileOf(existing(accountId).flatMap(person -> settingsRepository.findById(person.getId()))
				.orElseGet(OfficeHoursSettings::new));
	}

	@Transactional
	public OfficeHoursProfileResponse saveProfile(UUID accountId, UpdateOfficeHoursProfileRequest request) {
		InstitutionPerson record = requireAcademic(accountId);
		UUID instructorId = personFor(accountId, record).getId();
		OfficeHoursSettings settings = settingsRepository.findById(instructorId).orElseGet(() -> {
			OfficeHoursSettings created = new OfficeHoursSettings();
			created.setInstructorId(instructorId);
			return created;
		});
		settings.setListed(request.listed());
		settings.setDefaultPlace(TextUtils.normalizeOptional(request.defaultPlace()));
		settings.setOnlineLink(TextUtils.normalizeOptional(request.onlineLink()));
		settings.setContactEmail(TextUtils.normalizeOptional(request.contactEmail()));
		settings.setUpdatedAt(clock.instant());
		return profileOf(settingsRepository.saveAndFlush(settings));
	}

	/** Every request sent to the academician, newest first, from Menton or from any of their courses. */
	@Transactional(readOnly = true)
	public List<OfficeHoursInboxRequestResponse> inbox(UUID accountId) {
		requireAcademic(accountId);
		return existing(accountId)
				.map(person -> inboxItems(requestRepository.findByInstructorIdOrderByCreatedAtDesc(person.getId())))
				.orElse(List.of());
	}

	@Transactional
	public OfficeHoursInboxRequestResponse answer(UUID accountId, UUID requestId, AnswerOfficeHoursRequest answer) {
		requireAcademic(accountId);
		UUID instructorId = existing(accountId).map(OfficeHoursUser::getId)
				.orElseThrow(OfficeHoursMeService::requestNotFound);
		OfficeHoursRequest request = requestRepository.findById(requestId)
				.filter(found -> found.getInstructorId().equals(instructorId))
				.orElseThrow(OfficeHoursMeService::requestNotFound);
		if (!allowedActions(request).contains(answer.action())) {
			throw new InvalidRequestException(answer.action() == OfficeHoursRequestAction.ASK_QUESTION
					? "A question can only be asked on a course's request, where the student can answer it"
					: "This request can no longer be answered");
		}
		return inboxItems(List.of(bookingService.decide(request, instructorId, decision(answer)))).getFirst();
	}

	/** The academician's booked meetings, soonest first. */
	@Transactional(readOnly = true)
	public List<OfficeHoursAcademicianMeetingResponse> meetings(UUID accountId) {
		requireAcademic(accountId);
		Optional<OfficeHoursUser> person = existing(accountId);
		if (person.isEmpty()) {
			return List.of();
		}
		UUID instructorId = person.get().getId();
		List<OfficeHoursMeeting> meetings = meetingRepository.findByInstructorIdOrderByScheduledAtAsc(instructorId);
		if (meetings.isEmpty()) {
			return List.of();
		}
		Map<UUID, OfficeHoursRequest> requests = byId(
				requestRepository.findAllById(meetings.stream().map(OfficeHoursMeeting::getRequestId).toList()),
				OfficeHoursRequest::getId);
		Map<UUID, OfficeHoursStudentResponse> students = students(
				meetings.stream().map(OfficeHoursMeeting::getStudentId).toList());
		Map<UUID, String> courses = courseNames(meetings.stream().map(OfficeHoursMeeting::getCourseId).toList());
		OfficeHoursSettings settings = settingsRepository.findById(instructorId).orElseGet(OfficeHoursSettings::new);
		return meetings.stream()
				.map(meeting -> new OfficeHoursAcademicianMeetingResponse(meeting.getId(), meeting.getRequestId(),
						meeting.getScheduledAt(),
						meeting.getScheduledAt().plusSeconds(meeting.getDurationMinutes() * 60L),
						students.get(meeting.getStudentId()),
						Optional.ofNullable(requests.get(meeting.getRequestId())).map(OfficeHoursRequest::getTopic)
								.orElse(null),
						courseName(courses, meeting.getCourseId()), settings.getDefaultPlace(), settings.getOnlineLink()))
				.toList();
	}

	/** ODTÜ's record for the account, which must say they are an academic; otherwise there is nothing here. */
	private InstitutionPerson requireAcademic(UUID accountId) {
		return institutionPeople.findFirstByUserIdOrderBySyncedAtDesc(accountId)
				.filter(InstitutionPerson::isActive)
				.filter(InstitutionPerson::isAcademic)
				.orElseThrow(() -> new ResourceNotFoundException("Office hours not found."));
	}

	private Optional<OfficeHoursUser> existing(UUID accountId) {
		return userRepository.findByAppUserId(accountId);
	}

	/** The account's office-hours person, made on first use under the name ODTÜ's record gives. */
	private OfficeHoursUser personFor(UUID accountId, InstitutionPerson record) {
		Optional<OfficeHoursUser> found = existing(accountId);
		if (found.isEmpty()) {
			userRepository.insertForAccountIfAbsent(UUID.randomUUID(), accountId, record.getFullName(),
					clock.instant());
			found = existing(accountId);
		}
		OfficeHoursUser person = found.orElseThrow(() -> new IllegalStateException("Office-hours person missing"));
		if (!Objects.equals(person.getFullName(), record.getFullName())) {
			person.setFullName(record.getFullName());
			userRepository.save(person);
		}
		return person;
	}

	private List<OfficeHoursInboxRequestResponse> inboxItems(List<OfficeHoursRequest> requests) {
		if (requests.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = requests.stream().map(OfficeHoursRequest::getId).toList();
		Map<UUID, List<OfficeHoursInboxRequestResponse.Message>> messages = messageRepository
				.findByRequestIdInOrderByCreatedAtAsc(ids).stream()
				.collect(Collectors.groupingBy(OfficeHoursRequestMessage::getRequestId,
						Collectors.mapping(OfficeHoursMeService::message, Collectors.toList())));
		Map<UUID, OfficeHoursMeeting> meetings = byId(meetingRepository.findByRequestIdIn(ids),
				OfficeHoursMeeting::getRequestId);
		Map<UUID, OfficeHoursStudentResponse> students = students(
				requests.stream().map(OfficeHoursRequest::getStudentId).toList());
		Map<UUID, String> courses = courseNames(requests.stream().map(OfficeHoursRequest::getCourseId).toList());
		Map<UUID, Integer> minutes = requests.stream().map(OfficeHoursRequest::getInstructorId).distinct()
				.collect(Collectors.toMap(Function.identity(), slotService::defaultMeetingMinutes));
		return requests.stream().map(request -> {
			int length = minutes.get(request.getInstructorId());
			OfficeHoursMeeting meeting = meetings.get(request.getId());
			return new OfficeHoursInboxRequestResponse(request.getId(), request.getStatus(),
					students.get(request.getStudentId()), request.getTopic(), request.getExpectedOutcome(),
					courseName(courses, request.getCourseId()), times(request.getPreferredSlots(), length),
					times(request.getProposedSlots(), length), messages.getOrDefault(request.getId(), List.of()),
					meeting == null ? null : new OfficeHoursTimeResponse(meeting.getScheduledAt(),
							meeting.getScheduledAt().plusSeconds(meeting.getDurationMinutes() * 60L)),
					request.getRejectionReason(), request.getCreatedAt(), allowedActions(request));
		}).toList();
	}

	/** Asking needs the student's reply step, which only the Moodle course pages have so far. */
	private static List<OfficeHoursRequestAction> allowedActions(OfficeHoursRequest request) {
		if (!ANSWERABLE.contains(request.getStatus())) {
			return List.of();
		}
		List<OfficeHoursRequestAction> actions = new ArrayList<>(List.of(OfficeHoursRequestAction.ACCEPT,
				OfficeHoursRequestAction.DECLINE, OfficeHoursRequestAction.PROPOSE_TIMES));
		if (request.getCourseId() != null && request.getStatus() == OfficeHoursRequestStatus.PENDING) {
			actions.add(OfficeHoursRequestAction.ASK_QUESTION);
		}
		return List.copyOf(actions);
	}

	private static DecideOfficeHoursRequest decision(AnswerOfficeHoursRequest answer) {
		String message = answer.message();
		return switch (answer.action()) {
			case ACCEPT -> {
				if (answer.time() == null) {
					throw new InvalidRequestException("Pick one of the times the student asked for");
				}
				yield new DecideOfficeHoursRequest(OfficeHoursRequestStatus.ACCEPTED, message,
						MeetingSlotTimes.formatMachineSlot(answer.time()), null);
			}
			case DECLINE -> new DecideOfficeHoursRequest(OfficeHoursRequestStatus.DECLINED, message, null, null);
			case PROPOSE_TIMES -> new DecideOfficeHoursRequest(OfficeHoursRequestStatus.COUNTER_PROPOSED, message,
					null, answer.times() == null ? List.of()
							: answer.times().stream().map(MeetingSlotTimes::formatMachineSlot).toList());
			case ASK_QUESTION -> new DecideOfficeHoursRequest(OfficeHoursRequestStatus.MORE_INFO_REQUESTED, message,
					null, null);
		};
	}

	/**
	 * Who sent each request, as the academician may see them: ODTÜ's name, department and class year for a Menton
	 * student; only the Moodle name for someone who came in through a course.
	 */
	private Map<UUID, OfficeHoursStudentResponse> students(Collection<UUID> officeHoursIds) {
		List<OfficeHoursUser> people = userRepository.findAllById(Set.copyOf(officeHoursIds));
		List<UUID> accounts = people.stream().map(OfficeHoursUser::getAppUserId).filter(Objects::nonNull).toList();
		Map<UUID, InstitutionPerson> records = accounts.isEmpty() ? Map.of()
				: institutionPeople.findByUserIdIn(accounts).stream()
						.collect(Collectors.toMap(record -> record.getUser().getId(), Function.identity(),
								BinaryOperator.maxBy(Comparator.comparing(InstitutionPerson::getSyncedAt))));
		return people.stream().collect(Collectors.toMap(OfficeHoursUser::getId, person -> {
			InstitutionPerson record = person.getAppUserId() == null ? null : records.get(person.getAppUserId());
			if (record == null) {
				return new OfficeHoursStudentResponse(
						person.getAppUserId() != null ? person.getAppUserId() : person.getId(), person.getFullName(),
						null, null);
			}
			return new OfficeHoursStudentResponse(person.getAppUserId(), record.getFullName(),
					record.getDepartmentName(), record.getClassYear());
		}));
	}

	private Map<UUID, String> courseNames(Collection<UUID> courseIds) {
		List<UUID> ids = courseIds.stream().filter(Objects::nonNull).distinct().toList();
		return ids.isEmpty() ? Map.of()
				: courseRepository.findAllById(ids).stream()
						.collect(Collectors.toMap(OfficeHoursCourse::getId, OfficeHoursCourse::getTitle));
	}

	/** A Menton request has no course; an immutable map refuses a null key, so it is not asked. */
	private static String courseName(Map<UUID, String> courses, UUID courseId) {
		return courseId == null ? null : courses.get(courseId);
	}

	private static List<OfficeHoursTimeResponse> times(String joined, int minutes) {
		if (joined == null || joined.isBlank()) {
			return List.of();
		}
		return Arrays.stream(joined.split(","))
				.map(MeetingSlotTimes::parseMachineSlot)
				.filter(Objects::nonNull)
				.map(startsAt -> new OfficeHoursTimeResponse(startsAt, startsAt.plusSeconds(minutes * 60L)))
				.toList();
	}

	private static OfficeHoursInboxRequestResponse.Message message(OfficeHoursRequestMessage message) {
		return new OfficeHoursInboxRequestResponse.Message(
				message.getType() == OfficeHoursMessageType.INSTRUCTOR_QUESTION ? OfficeHoursRole.INSTRUCTOR
						: OfficeHoursRole.STUDENT,
				message.getBody(), message.getCreatedAt());
	}

	private static OfficeHoursAvailabilityResponse availabilityOf(OfficeHoursSettings settings) {
		return new OfficeHoursAvailabilityResponse(List.of(), List.of(), settings.getStudentCapacity(),
				settings.getDefaultMeetingDurationMinutes(), settings.getMeetingBufferMinutes());
	}

	private static OfficeHoursProfileResponse profileOf(OfficeHoursSettings settings) {
		return new OfficeHoursProfileResponse(settings.isListed(), settings.getDefaultPlace(),
				settings.getOnlineLink(), settings.getContactEmail());
	}

	private static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> id) {
		return items.stream().collect(Collectors.toMap(id, Function.identity()));
	}

	private static ResourceNotFoundException requestNotFound() {
		return new ResourceNotFoundException("Request not found.");
	}
}
