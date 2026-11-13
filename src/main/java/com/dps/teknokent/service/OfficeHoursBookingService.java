package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import com.dps.teknokent.util.MeetingSlotTimes;
import com.dps.teknokent.util.Texts;
import com.dps.teknokent.util.TextUtils;

/**
 * Office hours booked the way a mentee books a mentor: the student sends a request with a note and up to three
 * times; the academic accepts one of them, declines, asks a question, or offers other times; the student answers
 * a question, takes an offered time, or withdraws. Accepting makes the meeting. Every time must be at least
 * {@link MeetingSlotTimes#MIN_BOOKING_LEAD} away, as for mentors.
 */
@Service
public class OfficeHoursBookingService {

	private static final Set<OfficeHoursRequestStatus> OPEN = Set.of(OfficeHoursRequestStatus.PENDING,
			OfficeHoursRequestStatus.MORE_INFO_REQUESTED, OfficeHoursRequestStatus.COUNTER_PROPOSED);

	private final OfficeHoursEnrollmentRepository enrollmentRepository;
	private final OfficeHoursUserRepository userRepository;
	private final OfficeHoursRequestRepository requestRepository;
	private final OfficeHoursRequestMessageRepository messageRepository;
	private final OfficeHoursMeetingRepository meetingRepository;
	private final OfficeHoursSettingsRepository settingsRepository;
	private final OfficeHoursSlotService slotService;
	private final OfficeHoursCourseRepository courseRepository;
	private final Clock clock;

	@Autowired
	public OfficeHoursBookingService(OfficeHoursEnrollmentRepository enrollmentRepository,
			OfficeHoursUserRepository userRepository, OfficeHoursRequestRepository requestRepository,
			OfficeHoursRequestMessageRepository messageRepository, OfficeHoursMeetingRepository meetingRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursSlotService slotService,
			OfficeHoursCourseRepository courseRepository) {
		this(enrollmentRepository, userRepository, requestRepository, messageRepository, meetingRepository,
				settingsRepository, slotService, courseRepository, Clock.systemUTC());
	}

	OfficeHoursBookingService(OfficeHoursEnrollmentRepository enrollmentRepository,
			OfficeHoursUserRepository userRepository, OfficeHoursRequestRepository requestRepository,
			OfficeHoursRequestMessageRepository messageRepository, OfficeHoursMeetingRepository meetingRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursSlotService slotService,
			OfficeHoursCourseRepository courseRepository, Clock clock) {
		this.enrollmentRepository = enrollmentRepository;
		this.userRepository = userRepository;
		this.requestRepository = requestRepository;
		this.messageRepository = messageRepository;
		this.meetingRepository = meetingRepository;
		this.settingsRepository = settingsRepository;
		this.slotService = slotService;
		this.courseRepository = courseRepository;
		this.clock = clock;
	}

	/** The course's academics, as anyone in the course sees them. */
	@Transactional(readOnly = true)
	public List<OfficeHoursPersonResponse> instructors(UUID userId, UUID courseId) {
		requireRole(userId, courseId, null);
		List<UUID> ids = enrollmentRepository.findByCourseIdAndRole(courseId, OfficeHoursRole.INSTRUCTOR).stream()
				.map(OfficeHoursEnrollment::getUserId).toList();
		return userRepository.findAllById(ids).stream()
				.map(OfficeHoursBookingService::person)
				.sorted((left, right) -> left.fullName().compareToIgnoreCase(right.fullName()))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<OfficeHoursSlotResponse> slots(UUID userId, UUID courseId, UUID instructorId) {
		requireRole(userId, courseId, null);
		requireRole(instructorId, courseId, OfficeHoursRole.INSTRUCTOR);
		return slotService.openSlots(instructorId).stream()
				.map(slot -> new OfficeHoursSlotResponse(slot.startsAt(), slot.durationMinutes()))
				.toList();
	}

	/** A student sees their own requests in the course; an academic sees the ones sent to them there. */
	@Transactional(readOnly = true)
	public List<OfficeHoursRequestResponse> requests(UUID userId, UUID courseId) {
		OfficeHoursRole role = requireRole(userId, courseId, null);
		return responses(visibleRequests(userId, courseId, role), role);
	}

	@Transactional(readOnly = true)
	public List<OfficeHoursMeetingResponse> upcomingMeetings(UUID userId, UUID courseId) {
		Instant now = clock.instant();
		List<OfficeHoursRequest> mine = visibleRequests(userId, courseId, requireRole(userId, courseId, null));
		Map<UUID, OfficeHoursRequest> byId = mine.stream()
				.collect(Collectors.toMap(OfficeHoursRequest::getId, Function.identity()));
		List<OfficeHoursMeeting> meetings = meetingRepository.findByRequestIdIn(byId.keySet()).stream()
				.filter(meeting -> meeting.getScheduledAt().isAfter(now))
				.sorted((left, right) -> left.getScheduledAt().compareTo(right.getScheduledAt()))
				.toList();
		Map<UUID, OfficeHoursUser> people = people(meetings.stream()
				.flatMap(meeting -> Stream.of(meeting.getStudentId(), meeting.getInstructorId())).toList());
		Map<UUID, String> courseNames = courseNames(meetings.stream().map(OfficeHoursMeeting::getCourseId).toList());
		return meetings.stream()
				.map(meeting -> new OfficeHoursMeetingResponse(meeting.getId(), meeting.getRequestId(),
						meeting.getScheduledAt(), meeting.getDurationMinutes(),
						person(people.get(meeting.getStudentId())), person(people.get(meeting.getInstructorId())),
						byId.get(meeting.getRequestId()).getTopic(),
						OfficeHoursTimeResponse.of(meeting.getScheduledAt(), meeting.getDurationMinutes()),
						courseNames.get(meeting.getCourseId()), List.of()))
				.toList();
	}

	@Transactional
	public OfficeHoursRequestResponse create(UUID studentId, UUID courseId, CreateOfficeHoursRequest submission) {
		requireRole(studentId, courseId, OfficeHoursRole.STUDENT);
		requireRole(submission.instructorId(), courseId, OfficeHoursRole.INSTRUCTOR);
		List<String> slots = submission.preferredSlots().stream().distinct().toList();
		requireBookable(slots);
		requireNotHeld(submission.instructorId(), slots, null);
		Instant now = clock.instant();
		requireRoomFor(studentId, submission.instructorId(), now);

		OfficeHoursRequest request = new OfficeHoursRequest();
		request.setId(UUID.randomUUID());
		request.setCourseId(courseId);
		request.setStudentId(studentId);
		request.setInstructorId(submission.instructorId());
		request.setStatus(OfficeHoursRequestStatus.PENDING);
		request.setTopic(TextUtils.trim(submission.topic()));
		request.setExpectedOutcome(TextUtils.normalizeOptional(submission.expectedOutcome()));
		request.setPreferredSlots(String.join(",", slots));
		request.setCreatedAt(now);
		request.setUpdatedAt(now);
		return responses(List.of(requestRepository.saveAndFlush(request)), OfficeHoursRole.STUDENT).getFirst();
	}

	@Transactional
	public OfficeHoursRequestResponse decide(UUID instructorId, UUID courseId, UUID requestId,
			DecideOfficeHoursRequest decision) {
		OfficeHoursRequest request = requireRequest(requestId, courseId);
		if (!request.getInstructorId().equals(instructorId)) {
			throw forbidden("Only the academic the request was sent to can answer it.");
		}
		if (request.getStatus() != OfficeHoursRequestStatus.PENDING
				&& request.getStatus() != OfficeHoursRequestStatus.MORE_INFO_REQUESTED) {
			throw new InvalidRequestException("Only pending requests can be decided");
		}
		String message = TextUtils.normalizeOptional(decision.message());
		switch (decision.status()) {
			case ACCEPTED -> {
				String slot = decision.slot() != null ? decision.slot() : firstFreePreferred(request);
				if (slot == null || !slots(request.getPreferredSlots()).contains(slot)) {
					throw new InvalidRequestException("Pick one of the times the student asked for");
				}
				requireBookable(List.of(slot));
				request.setInstructorMessage(message);
				request.setStatus(OfficeHoursRequestStatus.ACCEPTED);
				book(request, slot);
			}
			case DECLINED -> {
				request.setRejectionReason(message);
				request.setStatus(OfficeHoursRequestStatus.DECLINED);
			}
			case MORE_INFO_REQUESTED -> {
				if (message == null) {
					throw new InvalidRequestException("Write the question for the student");
				}
				saveMessage(request, instructorId, OfficeHoursMessageType.INSTRUCTOR_QUESTION, message);
				request.setStatus(OfficeHoursRequestStatus.MORE_INFO_REQUESTED);
			}
			case COUNTER_PROPOSED -> {
				List<String> proposed = decision.proposedSlots() == null ? List.of()
						: decision.proposedSlots().stream().distinct().toList();
				if (proposed.isEmpty()) {
					throw new InvalidRequestException("At least one proposed slot is required");
				}
				requireBookable(proposed);
				requireNotHeld(request.getInstructorId(), proposed, request.getId());
				request.setProposedSlots(String.join(",", proposed));
				request.setInstructorMessage(message);
				request.setStatus(OfficeHoursRequestStatus.COUNTER_PROPOSED);
			}
			default -> throw new InvalidRequestException(
					"Decision status must be ACCEPTED, DECLINED, MORE_INFO_REQUESTED, or COUNTER_PROPOSED");
		}
		return save(request, OfficeHoursRole.INSTRUCTOR);
	}

	@Transactional
	public OfficeHoursRequestResponse acceptProposedSlot(UUID studentId, UUID courseId, UUID requestId,
			AcceptOfficeHoursSlotRequest acceptance) {
		OfficeHoursRequest request = requireOwnRequest(studentId, courseId, requestId);
		if (request.getStatus() != OfficeHoursRequestStatus.COUNTER_PROPOSED) {
			throw new InvalidRequestException("Only counter-proposed requests can accept a slot");
		}
		if (!slots(request.getProposedSlots()).contains(acceptance.slot())) {
			throw new InvalidRequestException("Selected slot is not among the academic's proposals");
		}
		requireBookable(List.of(acceptance.slot()));
		request.setStatus(OfficeHoursRequestStatus.ACCEPTED);
		book(request, acceptance.slot());
		return save(request, OfficeHoursRole.STUDENT);
	}

	@Transactional
	public OfficeHoursRequestResponse reply(UUID studentId, UUID courseId, UUID requestId,
			ReplyOfficeHoursRequest reply) {
		OfficeHoursRequest request = requireOwnRequest(studentId, courseId, requestId);
		if (request.getStatus() != OfficeHoursRequestStatus.MORE_INFO_REQUESTED) {
			throw new InvalidRequestException("Only a request with a question can be answered");
		}
		saveMessage(request, studentId, OfficeHoursMessageType.STUDENT_REPLY, TextUtils.trim(reply.message()));
		request.setStatus(OfficeHoursRequestStatus.PENDING);
		return save(request, OfficeHoursRole.STUDENT);
	}

	@Transactional
	public OfficeHoursRequestResponse withdraw(UUID studentId, UUID courseId, UUID requestId) {
		OfficeHoursRequest request = requireOwnRequest(studentId, courseId, requestId);
		if (!OPEN.contains(request.getStatus())) {
			throw new InvalidRequestException("Only an unanswered request can be withdrawn");
		}
		request.setStatus(OfficeHoursRequestStatus.WITHDRAWN);
		return save(request, OfficeHoursRole.STUDENT);
	}

	/**
	 * The academic's student limit, read the way a mentor's mentee capacity is: how many students they work with at
	 * once. Students with an open request or an upcoming meeting count; someone already among them can always ask
	 * again. No limit when none is set.
	 */
	private void requireRoomFor(UUID studentId, UUID instructorId, Instant now) {
		Integer capacity = settingsRepository.findById(instructorId).map(OfficeHoursSettings::getStudentCapacity)
				.orElse(null);
		if (capacity == null) {
			return;
		}
		Set<UUID> students = new HashSet<>();
		requestRepository.findByInstructorIdAndStatusIn(instructorId, OPEN)
				.forEach(open -> students.add(open.getStudentId()));
		meetingRepository.findByInstructorIdAndScheduledAtAfter(instructorId, now)
				.forEach(meeting -> students.add(meeting.getStudentId()));
		if (!students.contains(studentId) && students.size() >= capacity) {
			throw new ConflictException("This academic has no room for another student right now.");
		}
	}

	private void book(OfficeHoursRequest request, String slot) {
		Instant startsAt = MeetingSlotTimes.parseMachineSlot(slot);
		int duration = meetingMinutes(request.getInstructorId(), startsAt);
		if (slotService.clashesWithCalendar(request.getInstructorId(), startsAt, duration)) {
			throw new ConflictException("This time overlaps a meeting already on the calendar. Please choose another time");
		}
		OfficeHoursMeeting meeting = new OfficeHoursMeeting();
		meeting.setId(UUID.randomUUID());
		meeting.setRequestId(request.getId());
		meeting.setCourseId(request.getCourseId());
		meeting.setInstructorId(request.getInstructorId());
		meeting.setStudentId(request.getStudentId());
		meeting.setScheduledAt(startsAt);
		meeting.setDurationMinutes(duration);
		meeting.setCreatedAt(clock.instant());
		try {
			meetingRepository.saveAndFlush(meeting);
		} catch (DataIntegrityViolationException exception) {
			throw new ConflictException("This slot has just been booked. Please choose another time");
		}
	}

	/** An open slot's own length; a time typed by hand gets the academic's default meeting length. */
	private int meetingMinutes(UUID instructorId, Instant startsAt) {
		return slotService.openSlotAt(instructorId, startsAt)
				.map(OfficeHoursSlotService.Slot::durationMinutes)
				.orElseGet(() -> slotService.defaultMeetingMinutes(instructorId));
	}

	/** As for mentors, accepting without naming a time takes the student's first time that is still free. */
	private String firstFreePreferred(OfficeHoursRequest request) {
		return slots(request.getPreferredSlots()).stream()
				.filter(slot -> {
					Instant startsAt = MeetingSlotTimes.parseMachineSlot(slot);
					return !slotService.clashesWithCalendar(request.getInstructorId(), startsAt,
							meetingMinutes(request.getInstructorId(), startsAt));
				})
				.findFirst()
				.orElse(null);
	}

	/** A time another open request holds is not offered twice (FR-OH-16). */
	private void requireNotHeld(UUID instructorId, List<String> slots, UUID exceptRequestId) {
		Set<Instant> held = slotService.heldTimes(instructorId, exceptRequestId);
		if (slots.stream().map(MeetingSlotTimes::parseMachineSlot).anyMatch(held::contains)) {
			throw new ConflictException("Another request is waiting on one of these times. Please choose another time");
		}
	}

	private void requireBookable(List<String> slots) {
		if (slots.stream().anyMatch(slot -> MeetingSlotTimes.parseMachineSlot(slot) == null)) {
			throw new InvalidRequestException("Times must look like 2026-11-24T14:00");
		}
		Instant earliest = clock.instant().plus(MeetingSlotTimes.MIN_BOOKING_LEAD);
		if (slots.stream().map(MeetingSlotTimes::parseMachineSlot).anyMatch(at -> at.isBefore(earliest))) {
			throw new InvalidRequestException(Texts.forRequest("error.slotTooSoon"));
		}
	}

	private OfficeHoursRole requireRole(UUID userId, UUID courseId, OfficeHoursRole expected) {
		OfficeHoursEnrollment enrollment = enrollmentRepository.findByUserIdAndCourseId(userId, courseId)
				.orElseThrow(() -> new ResourceNotFoundException("Course not found."));
		if (expected != null && enrollment.getRole() != expected) {
			throw forbidden(expected == OfficeHoursRole.STUDENT
					? "Only the course's students can request office hours."
					: "That person is not an academic of this course.");
		}
		return enrollment.getRole();
	}

	private OfficeHoursRequest requireRequest(UUID requestId, UUID courseId) {
		return requestRepository.findById(requestId)
				.filter(request -> request.getCourseId().equals(courseId))
				.orElseThrow(() -> new ResourceNotFoundException("Request not found."));
	}

	private OfficeHoursRequest requireOwnRequest(UUID studentId, UUID courseId, UUID requestId) {
		OfficeHoursRequest request = requireRequest(requestId, courseId);
		if (!request.getStudentId().equals(studentId)) {
			throw forbidden("Only the student who sent the request can do this.");
		}
		return request;
	}

	private List<OfficeHoursRequest> visibleRequests(UUID userId, UUID courseId, OfficeHoursRole role) {
		return role == OfficeHoursRole.STUDENT
				? requestRepository.findByCourseIdAndStudentIdOrderByCreatedAtDesc(courseId, userId)
				: requestRepository.findByCourseIdAndInstructorIdOrderByCreatedAtDesc(courseId, userId);
	}

	private void saveMessage(OfficeHoursRequest request, UUID authorId, OfficeHoursMessageType type, String body) {
		OfficeHoursRequestMessage message = new OfficeHoursRequestMessage();
		message.setId(UUID.randomUUID());
		message.setRequestId(request.getId());
		message.setAuthorId(authorId);
		message.setType(type);
		message.setBody(body);
		message.setCreatedAt(clock.instant());
		messageRepository.save(message);
	}

	private OfficeHoursRequestResponse save(OfficeHoursRequest request, OfficeHoursRole viewer) {
		request.setUpdatedAt(clock.instant());
		return responses(List.of(requestRepository.saveAndFlush(request)), viewer).getFirst();
	}

	/** {@code viewer} is the caller's role in the course; it decides the allowed actions. */
	private List<OfficeHoursRequestResponse> responses(List<OfficeHoursRequest> requests, OfficeHoursRole viewer) {
		if (requests.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = requests.stream().map(OfficeHoursRequest::getId).toList();
		Map<UUID, List<OfficeHoursRequestResponse.Message>> messages = messageRepository
				.findByRequestIdInOrderByCreatedAtAsc(ids).stream()
				.collect(Collectors.groupingBy(OfficeHoursRequestMessage::getRequestId,
						Collectors.mapping(message -> new OfficeHoursRequestResponse.Message(message.getId(),
								message.getAuthorId(), message.getType(), message.getBody(), message.getCreatedAt()),
								Collectors.toList())));
		Map<UUID, OfficeHoursMeeting> meetings = meetingRepository.findByRequestIdIn(ids).stream()
				.collect(Collectors.toMap(OfficeHoursMeeting::getRequestId, Function.identity()));
		Map<UUID, OfficeHoursUser> people = people(requests.stream()
				.flatMap(request -> Stream.of(request.getStudentId(), request.getInstructorId())).toList());
		Map<UUID, String> courseNames = courseNames(requests.stream().map(OfficeHoursRequest::getCourseId).toList());
		return requests.stream().map(request -> {
			OfficeHoursMeeting meeting = meetings.get(request.getId());
			return new OfficeHoursRequestResponse(request.getId(), request.getCourseId(), request.getStatus(),
					person(people.get(request.getStudentId())), person(people.get(request.getInstructorId())),
					request.getTopic(), request.getExpectedOutcome(), slots(request.getPreferredSlots()),
					slots(request.getProposedSlots()), request.getInstructorMessage(), request.getRejectionReason(),
					messages.getOrDefault(request.getId(), List.of()),
					meeting == null ? null : new OfficeHoursSlotResponse(meeting.getScheduledAt(),
							meeting.getDurationMinutes()),
					request.getCreatedAt(), courseNames.get(request.getCourseId()),
					times(request.getInstructorId(), request.getPreferredSlots()),
					times(request.getInstructorId(), request.getProposedSlots()),
					OfficeHoursRequestAction.allowedFor(viewer, request.getStatus()));
		}).toList();
	}

	/** Each slot with the length booking it would get, as {@link #book} gives it. */
	private List<OfficeHoursTimeResponse> times(UUID instructorId, String joined) {
		return slots(joined).stream()
				.map(MeetingSlotTimes::parseMachineSlot)
				.filter(Objects::nonNull)
				.map(startsAt -> OfficeHoursTimeResponse.of(startsAt, meetingMinutes(instructorId, startsAt)))
				.toList();
	}

	/** Course-free requests (booked from Menton, without a Moodle course) have no name and are skipped. */
	private Map<UUID, String> courseNames(Collection<UUID> ids) {
		Set<UUID> courseIds = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
		return courseRepository.findAllById(courseIds).stream()
				.collect(Collectors.toMap(OfficeHoursCourse::getId, OfficeHoursCourse::getTitle));
	}

	private Map<UUID, OfficeHoursUser> people(Collection<UUID> ids) {
		return userRepository.findAllById(Set.copyOf(ids)).stream()
				.collect(Collectors.toMap(OfficeHoursUser::getId, Function.identity()));
	}

	private static OfficeHoursPersonResponse person(OfficeHoursUser user) {
		return user == null ? null : new OfficeHoursPersonResponse(user.getId(), user.getFullName());
	}

	private static List<String> slots(String joined) {
		if (joined == null || joined.isBlank()) {
			return List.of();
		}
		return Arrays.stream(joined.split(",")).map(String::trim).filter(slot -> !slot.isEmpty()).toList();
	}

	private static ResponseStatusException forbidden(String reason) {
		return new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
	}
}
