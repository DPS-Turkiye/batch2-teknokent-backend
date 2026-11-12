package com.dps.teknokent.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * An academic's office hours: weekly ranges, dates away and meeting defaults, saved as a whole and checked with
 * the same rules as a mentor's availability. Like a mentor's, they belong to the person and apply to every course
 * they teach; only someone Moodle made an instructor somewhere may set them. Bookable slots are made from these
 * when students start booking.
 */
@Service
public class OfficeHoursAvailabilityService {

	private static final int DEFAULT_SLOT_MINUTES = 60;

	private final OfficeHoursEnrollmentRepository enrollmentRepository;
	private final OfficeHoursSettingsRepository settingsRepository;
	private final OfficeHoursRuleRepository ruleRepository;
	private final OfficeHoursExceptionRepository exceptionRepository;
	private final Clock clock;

	@Autowired
	public OfficeHoursAvailabilityService(OfficeHoursEnrollmentRepository enrollmentRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursRuleRepository ruleRepository,
			OfficeHoursExceptionRepository exceptionRepository) {
		this(enrollmentRepository, settingsRepository, ruleRepository, exceptionRepository, Clock.systemUTC());
	}

	OfficeHoursAvailabilityService(OfficeHoursEnrollmentRepository enrollmentRepository,
			OfficeHoursSettingsRepository settingsRepository, OfficeHoursRuleRepository ruleRepository,
			OfficeHoursExceptionRepository exceptionRepository, Clock clock) {
		this.enrollmentRepository = enrollmentRepository;
		this.settingsRepository = settingsRepository;
		this.ruleRepository = ruleRepository;
		this.exceptionRepository = exceptionRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public OfficeHoursAvailabilityResponse find(UUID instructorId) {
		requireInstructor(instructorId);
		return read(instructorId);
	}

	/** Someone's office hours, once the caller is known to be an academic (Moodle or Menton). */
	OfficeHoursAvailabilityResponse read(UUID instructorId) {
		OfficeHoursSettings settings = settingsRepository.findById(instructorId).orElseGet(OfficeHoursSettings::new);
		return response(instructorId, settings);
	}

	@Transactional
	public OfficeHoursAvailabilityResponse update(UUID instructorId, UpdateOfficeHoursAvailabilityRequest request) {
		requireInstructor(instructorId);
		return write(instructorId, request);
	}

	/** Saves someone's office hours as a whole, once the caller is known to be an academic. */
	OfficeHoursAvailabilityResponse write(UUID instructorId, UpdateOfficeHoursAvailabilityRequest request) {
		validateRules(request.rules());
		validateExceptions(request.exceptions());
		Instant now = clock.instant();

		ruleRepository.deleteAll(ruleRepository.findByInstructorIdOrderByDayOfWeekAscStartTimeAsc(instructorId));
		exceptionRepository.deleteAll(exceptionRepository.findByInstructorIdOrderByStartsOnAsc(instructorId));
		// Deletes must reach the database first: the replacements may reuse the same unique day/time and dates.
		ruleRepository.flush();
		exceptionRepository.flush();

		OfficeHoursSettings settings = settingsRepository.findById(instructorId).orElseGet(() -> {
			OfficeHoursSettings created = new OfficeHoursSettings();
			created.setInstructorId(instructorId);
			return created;
		});
		settings.setStudentCapacity(request.studentCapacity());
		settings.setDefaultMeetingDurationMinutes(request.defaultMeetingDurationMinutes());
		settings.setMeetingBufferMinutes(request.meetingBufferMinutes());
		settings.setUpdatedAt(now);
		settingsRepository.save(settings);

		ruleRepository.saveAll(request.rules().stream().map(rule -> newRule(instructorId, rule, now)).toList());
		exceptionRepository.saveAll(request.exceptions().stream()
				.map(exception -> newException(instructorId, exception)).toList());
		ruleRepository.flush();
		exceptionRepository.flush();
		return response(instructorId, settings);
	}

	private void requireInstructor(UUID userId) {
		if (!enrollmentRepository.existsByUserIdAndRole(userId, OfficeHoursRole.INSTRUCTOR)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only academics set office hours.");
		}
	}

	private OfficeHoursAvailabilityResponse response(UUID instructorId, OfficeHoursSettings settings) {
		return new OfficeHoursAvailabilityResponse(
				ruleRepository.findByInstructorIdOrderByDayOfWeekAscStartTimeAsc(instructorId)
						.stream().map(OfficeHoursAvailabilityResponse.Rule::fromEntity).toList(),
				exceptionRepository.findByInstructorIdOrderByStartsOnAsc(instructorId)
						.stream().map(OfficeHoursAvailabilityResponse.DateOff::fromEntity).toList(),
				settings.getStudentCapacity(),
				settings.getDefaultMeetingDurationMinutes(),
				settings.getMeetingBufferMinutes());
	}

	/** The same checks a mentor's weekly availability gets. */
	static void validateRules(List<CreateMentorAvailabilityRuleRequest> rules) {
		Set<String> seen = new HashSet<>();
		for (CreateMentorAvailabilityRuleRequest rule : rules) {
			if (!rule.endTime().isAfter(rule.startTime())) {
				throw new InvalidRequestException("Availability must end after it starts");
			}
			if (!seen.add(rule.dayOfWeek() + ":" + rule.startTime())) {
				throw new InvalidRequestException("Duplicate weekly availability rule");
			}
		}
		for (CreateMentorAvailabilityRuleRequest left : rules) {
			for (CreateMentorAvailabilityRuleRequest right : rules) {
				if (left == right || left.dayOfWeek() != right.dayOfWeek()) {
					continue;
				}
				if (left.startTime().isBefore(right.endTime()) && right.startTime().isBefore(left.endTime())) {
					throw new InvalidRequestException("Weekly availability ranges cannot overlap");
				}
			}
		}
	}

	static void validateExceptions(List<MentorAvailabilityExceptionRequest> exceptions) {
		Set<String> seen = new HashSet<>();
		for (MentorAvailabilityExceptionRequest exception : exceptions) {
			if (exception.endsOn().isBefore(exception.startsOn())) {
				throw new InvalidRequestException("Availability exception must end on or after it starts");
			}
			if (!seen.add(exception.startsOn() + ":" + exception.endsOn())) {
				throw new InvalidRequestException("Duplicate availability exception dates");
			}
		}
	}

	private static OfficeHoursRule newRule(UUID instructorId, CreateMentorAvailabilityRuleRequest request,
			Instant now) {
		OfficeHoursRule rule = new OfficeHoursRule();
		rule.setId(UUID.randomUUID());
		rule.setInstructorId(instructorId);
		rule.setDay(request.dayOfWeek());
		rule.setStartTime(request.startTime());
		rule.setEndTime(request.endTime());
		rule.setSlotMinutes(request.slotMinutes() != null ? request.slotMinutes() : DEFAULT_SLOT_MINUTES);
		rule.setCreatedAt(now);
		return rule;
	}

	private static OfficeHoursException newException(UUID instructorId, MentorAvailabilityExceptionRequest request) {
		OfficeHoursException exception = new OfficeHoursException();
		exception.setId(UUID.randomUUID());
		exception.setInstructorId(instructorId);
		exception.setStartsOn(request.startsOn());
		exception.setEndsOn(request.endsOn());
		exception.setReason(request.reason() == null || request.reason().isBlank() ? null : request.reason().trim());
		return exception;
	}
}
