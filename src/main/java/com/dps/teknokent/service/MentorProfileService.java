package com.dps.teknokent.service;

import java.util.stream.Collectors;
import java.util.Map;
import com.dps.teknokent.repository.ProgramMentorRepository;
import com.dps.teknokent.repository.MentorshipRepository;
import com.dps.teknokent.repository.MentorProgramName;
import com.dps.teknokent.repository.MentorCount;
import com.dps.teknokent.domain.ProgramMentorStatus;
import com.dps.teknokent.domain.MentorshipStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilityRuleResponse;
import com.dps.teknokent.entity.MentorAvailabilityRule;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import com.dps.teknokent.repository.MentorAvailabilityExceptionRepository;
import com.dps.teknokent.entity.MentorAvailabilityException;
import com.dps.teknokent.dto.requests.UpdateMentorAvailabilitySettingsRequest;
import com.dps.teknokent.dto.requests.MentorAvailabilityExceptionRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilityExceptionResponse;
import com.dps.teknokent.dto.responses.MentorAvailabilitySettingsResponse;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.requests.MentorProgramRoleRequest;
import com.dps.teknokent.dto.requests.UpdateMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorProgramResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.entity.ProgramMentor;
import com.dps.teknokent.util.TextUtils;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorAvailabilitySlotRepository;
import com.dps.teknokent.repository.MentorProfileRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorProfileService {

	private final MentorProfileRepository mentorProfileRepository;
	private final MentorAvailabilityRuleRepository mentorAvailabilityRuleRepository;
	private final MentorAvailabilityExceptionRepository mentorAvailabilityExceptionRepository;
	private final MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	private final MeetingRepository meetingRepository;
	private final DomainLookupService domainLookupService;
	private final MentorshipRepository mentorshipRepository;
	private final ProgramMentorRepository programMentorRepository;
	private final MenteeProgramService menteeProgramService;

	private static final List<MentorshipStatus> ONGOING_MENTORSHIP = List.of(
			MentorshipStatus.PLANNED, MentorshipStatus.ACTIVE, MentorshipStatus.PAUSED);

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAll() {
		return withActivity(approvedMentors());
	}

	/** The directory as {@code actorId} sees it: a mentee in a program gets only that program's accepted mentors. */
	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findReachable(UUID actorId) {
		return menteeProgramService.reachableMentorIds(actorId)
				.map(ids -> withActivity(approvedMentors().stream()
						.filter(profile -> ids.contains(profile.getId()))
						.toList()))
				.orElseGet(this::findAll);
	}

	/** Every approved mentor of {@code mentorIds} except one, for offering someone else. */
	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAmongExcept(Set<UUID> mentorIds, UUID excludedMentorId) {
		return withActivity(approvedMentors().stream()
				.filter(profile -> mentorIds.contains(profile.getId()) && !profile.getId().equals(excludedMentorId))
				.toList());
	}

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAllExcept(UUID excludedMentorId) {
		return withActivity(approvedMentors().stream()
				.filter(profile -> !profile.getId().equals(excludedMentorId))
				.toList());
	}

	/**
	 * Rules are weekly and local ("Salı 14:00-17:00"), but a bookable slot is a
	 * concrete instant, so rules are materialised over a rolling horizon. Turkey
	 * has a single fixed offset, so one business zone is enough here; a mentor
	 * working from another zone would need this stored per profile.
	 */
	private static final ZoneId BUSINESS_ZONE = ZoneId.of("Europe/Istanbul");
	private static final int HORIZON_WEEKS = 8;

	@Transactional(readOnly = true)
	public List<MentorAvailabilityRuleResponse> findAvailabilityRules(UUID mentorId) {
		requireMentor(mentorId);
		return mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId)
				.stream()
				.map(MentorAvailabilityRuleResponse::fromEntity)
				.toList();
	}

	@Transactional(readOnly = true)
	public MentorAvailabilitySettingsResponse findAvailabilitySettings(UUID mentorId) {
		MentorProfile profile = mentorProfileRepository.findOneById(mentorId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentor profile not found: " + mentorId));
		return availabilitySettings(profile);
	}

	@Transactional
	public MentorAvailabilitySettingsResponse updateAvailabilitySettings(
			UUID mentorId, UpdateMentorAvailabilitySettingsRequest request) {
		AppUser mentor = requireMentor(mentorId);
		MentorProfile profile = mentorProfileRepository.findOneById(mentorId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentor profile not found: " + mentorId));
		validateRules(request.rules());
		validateExceptions(request.exceptions());
		mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId)
				.forEach(rule -> deleteAvailabilityRule(mentorId, rule.getId()));
		mentorAvailabilityExceptionRepository.deleteAll(mentorAvailabilityExceptionRepository.findByMentorId(mentorId));
		// Hibernate flushes inserts before deletes and the dates are unique per mentor,
		// so re-saving unchanged dates would collide with the rows being replaced.
		mentorAvailabilityExceptionRepository.flush();
		if (request.menteeCapacity() != null) {
			profile.setMenteeCapacity(request.menteeCapacity());
		}
		profile.setDefaultMeetingDurationMinutes(request.defaultMeetingDurationMinutes());
		profile.setMeetingBufferMinutes(request.meetingBufferMinutes());
		mentorProfileRepository.save(profile);
		request.exceptions().forEach(exception -> saveException(mentor, exception));
		request.rules().forEach(rule -> createAvailabilityRule(mentorId, rule));
		removeUnbookedSlotsInExceptions(mentorId);
		return availabilitySettings(profile);
	}

	private MentorAvailabilitySettingsResponse availabilitySettings(MentorProfile profile) {
		UUID mentorId = profile.getId();
		return new MentorAvailabilitySettingsResponse(
				mentorAvailabilityRuleRepository.findByMentorIdOrderByDayOfWeekAscStartTimeAsc(mentorId).stream()
						.map(MentorAvailabilityRuleResponse::fromEntity).toList(),
				mentorAvailabilityExceptionRepository.findByMentorIdOrderByStartsOnAsc(mentorId).stream()
						.map(MentorAvailabilityExceptionResponse::fromEntity).toList(),
				profile.getMenteeCapacity(),
				profile.getDefaultMeetingDurationMinutes(), profile.getMeetingBufferMinutes());
	}

	private void validateRules(List<CreateMentorAvailabilityRuleRequest> rules) {
		Set<String> seen = new HashSet<>();
		for (CreateMentorAvailabilityRuleRequest rule : rules) {
			if (!rule.endTime().isAfter(rule.startTime())) throw new InvalidRequestException("Availability must end after it starts");
			if (!seen.add(rule.dayOfWeek() + ":" + rule.startTime())) throw new InvalidRequestException("Duplicate weekly availability rule");
		}
		for (CreateMentorAvailabilityRuleRequest left : rules) for (CreateMentorAvailabilityRuleRequest right : rules) {
			if (left == right || left.dayOfWeek() != right.dayOfWeek()) continue;
			if (left.startTime().isBefore(right.endTime()) && right.startTime().isBefore(left.endTime()))
				throw new InvalidRequestException("Weekly availability ranges cannot overlap");
		}
	}

	private void validateExceptions(List<MentorAvailabilityExceptionRequest> exceptions) {
		Set<String> seen = new HashSet<>();
		for (MentorAvailabilityExceptionRequest exception : exceptions) {
			if (exception.endsOn().isBefore(exception.startsOn()))
				throw new InvalidRequestException("Availability exception must end on or after it starts");
			// The table is unique on these dates, so a repeat would otherwise surface as a 500.
			if (!seen.add(exception.startsOn() + ":" + exception.endsOn()))
				throw new InvalidRequestException("Duplicate availability exception dates");
		}
	}

	private void saveException(AppUser mentor, MentorAvailabilityExceptionRequest request) {
		MentorAvailabilityException exception = new MentorAvailabilityException();
		exception.setMentor(mentor);
		exception.setStartsOn(request.startsOn());
		exception.setEndsOn(request.endsOn());
		exception.setReason(request.reason() == null || request.reason().isBlank() ? null : request.reason().trim());
		mentorAvailabilityExceptionRepository.save(exception);
	}

	private void removeUnbookedSlotsInExceptions(UUID mentorId) {
		mentorAvailabilitySlotRepository.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(mentorId, Instant.now()).stream()
				.filter(slot -> isExcluded(mentorId, slot.getStartsAt().atZone(BUSINESS_ZONE).toLocalDate()))
				.filter(slot -> !isBooked(slot))
				.forEach(mentorAvailabilitySlotRepository::delete);
	}

	@Transactional
	public MentorAvailabilityRuleResponse createAvailabilityRule(
			UUID mentorId, CreateMentorAvailabilityRuleRequest request) {
		AppUser mentor = requireMentor(mentorId);
		if (!request.endTime().isAfter(request.startTime())) {
			throw new InvalidRequestException("Availability must end after it starts");
		}
		short day = (short) request.dayOfWeek().getValue();
		if (mentorAvailabilityRuleRepository
				.existsByMentorIdAndDayOfWeekAndStartTime(mentorId, day, request.startTime())) {
			throw new InvalidRequestException("A rule already starts at this time on that day");
		}

		MentorAvailabilityRule rule = new MentorAvailabilityRule();
		rule.setMentor(mentor);
		rule.setDay(request.dayOfWeek());
		rule.setStartTime(request.startTime());
		rule.setEndTime(request.endTime());
		rule.setSlotMinutes(request.slotMinutes() != null ? request.slotMinutes() : 60);
		rule.setCreatedAt(Instant.now());
		MentorAvailabilityRule saved = mentorAvailabilityRuleRepository.saveAndFlush(rule);

		materialise(mentor, saved);
		return MentorAvailabilityRuleResponse.fromEntity(saved);
	}

	/**
	 * Withdraws the rule and the future slots it generated. Booked slots are kept:
	 * a mentee has already committed to them, and a mentor changing next month's
	 * pattern must not silently cancel an agreed meeting.
	 */
	@Transactional
	public void deleteAvailabilityRule(UUID mentorId, UUID ruleId) {
		requireMentor(mentorId);
		MentorAvailabilityRule rule = mentorAvailabilityRuleRepository
				.findByIdAndMentorId(ruleId, mentorId)
				.orElseThrow(() -> new ResourceNotFoundException("Availability rule not found: " + ruleId));

		List<MentorAvailabilitySlot> removable = new ArrayList<>();
		for (MentorAvailabilitySlot slot : mentorAvailabilitySlotRepository.findByRuleIdAndStartsAtAfter(ruleId, Instant.now())) {
			if (isBooked(slot)) {
				// A kept slot still pointing at the deleted rule makes Hibernate refuse the flush.
				slot.setRule(null);
			} else {
				removable.add(slot);
			}
		}
		mentorAvailabilitySlotRepository.deleteAll(removable);
		mentorAvailabilityRuleRepository.delete(rule);
	}

	private boolean isBooked(MentorAvailabilitySlot slot) {
		return meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
				slot.getMentor().getId(), slot.getStartsAt(), MeetingStatus.SCHEDULED);
	}

	private void materialise(AppUser mentor, MentorAvailabilityRule rule) {
		ZonedDateTime cursor = ZonedDateTime.now(BUSINESS_ZONE)
				.with(TemporalAdjusters.nextOrSame(rule.day()))
				.with(rule.getStartTime());
		ZonedDateTime horizon = ZonedDateTime.now(BUSINESS_ZONE).plusWeeks(HORIZON_WEEKS);

		List<MentorAvailabilitySlot> created = new ArrayList<>();
		while (cursor.isBefore(horizon)) {
			ZonedDateTime dayEnd = cursor.with(rule.getEndTime());
			for (ZonedDateTime at = cursor; !at.plusMinutes(rule.getSlotMinutes()).isAfter(dayEnd);
					at = at.plusMinutes(rule.getSlotMinutes())) {
				Instant startsAt = at.toInstant();
				// Skip anything already past, and anything the mentor added by hand.
				if (!startsAt.isAfter(Instant.now())
						|| isExcluded(mentor.getId(), at.toLocalDate())
						|| mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(mentor.getId(), startsAt)) {
					continue;
				}
				MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
				slot.setMentor(mentor);
				slot.setStartsAt(startsAt);
				slot.setDurationMinutes(rule.getSlotMinutes());
				slot.setRule(rule);
				slot.setCreatedAt(Instant.now());
				created.add(slot);
			}
			cursor = cursor.plusWeeks(1);
		}
		mentorAvailabilitySlotRepository.saveAll(created);
	}

	private boolean isExcluded(UUID mentorId, LocalDate day) {
		return mentorAvailabilityExceptionRepository
				.existsByMentorIdAndStartsOnLessThanEqualAndEndsOnGreaterThanEqual(mentorId, day, day);
	}

	private List<MentorProfile> approvedMentors() {
		return mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED);
	}

	// Counts come from grouped queries so a mentor list costs three queries, not three per mentor.
	private List<MentorProfileResponse> withActivity(List<MentorProfile> profiles) {
		if (profiles.isEmpty()) {
			return List.of();
		}
		List<UUID> ids = profiles.stream().map(MentorProfile::getId).toList();
		Map<UUID, Long> active = totals(mentorshipRepository.countOrganizationsByMentor(ids, ONGOING_MENTORSHIP));
		Map<UUID, Long> completed = totals(
				mentorshipRepository.countMentorshipsByMentor(ids, MentorshipStatus.COMPLETED));
		Map<UUID, List<MentorProgramResponse>> programs = programMentorRepository
				.findProgramNamesByMentorIds(ids, ProgramMentorStatus.ACCEPTED).stream()
				.collect(Collectors.groupingBy(MentorProgramName::getMentorId,
						Collectors.mapping(row -> new MentorProgramResponse(
								row.getProgramId(), row.getProgramName(), row.getProgramStatus(), row.getRole()),
								Collectors.toList())));
		return profiles.stream()
				.map(profile -> MentorProfileResponse.fromEntity(profile).withActivity(
						active.getOrDefault(profile.getId(), 0L),
						completed.getOrDefault(profile.getId(), 0L),
						programs.getOrDefault(profile.getId(), List.of())))
				.toList();
	}

	private static Map<UUID, Long> totals(List<MentorCount> counts) {
		return counts.stream().collect(Collectors.toMap(MentorCount::getMentorId, MentorCount::getTotal));
	}

	@Transactional(readOnly = true)
	public MentorProfileResponse findById(UUID id) {
		return mentorProfileRepository.findOneById(id)
				.map(profile -> withActivity(List.of(profile)).getFirst())
				.orElseThrow(() -> new ResourceNotFoundException("Mentor not found: " + id));
	}

	@Transactional(readOnly = true)
	public List<MentorAvailabilitySlotResponse> findAvailability(UUID mentorId) {
		requireMentor(mentorId);
		return mentorAvailabilitySlotRepository
				.findByMentorIdAndStartsAtAfterOrderByStartsAtAsc(mentorId, Instant.now())
				.stream()
				.map(slot -> new MentorAvailabilitySlotResponse(
						slot.getId(),
						slot.getStartsAt(),
						slot.getDurationMinutes(),
						meetingRepository.existsByMentorIdAndScheduledAtAndStatus(
								mentorId, slot.getStartsAt(), MeetingStatus.SCHEDULED)))
				.toList();
	}

	@Transactional
	public MentorAvailabilitySlotResponse createAvailabilitySlot(UUID mentorId, CreateMentorAvailabilitySlotRequest request) {
		AppUser mentor = requireMentor(mentorId);
		if (!request.startsAt().isAfter(Instant.now())) {
			throw new InvalidRequestException("Availability slot must be in the future");
		}
		if (mentorAvailabilitySlotRepository.existsByMentorIdAndStartsAt(mentorId, request.startsAt())) {
			throw new InvalidRequestException("A slot already exists at this time");
		}

		MentorAvailabilitySlot slot = new MentorAvailabilitySlot();
		slot.setMentor(mentor);
		slot.setStartsAt(request.startsAt());
		slot.setDurationMinutes(request.durationMinutes() != null ? request.durationMinutes() : 60);
		MentorAvailabilitySlot saved = mentorAvailabilitySlotRepository.saveAndFlush(slot);
		return new MentorAvailabilitySlotResponse(saved.getId(), saved.getStartsAt(), saved.getDurationMinutes(), false);
	}

	@Transactional
	public MentorProfileResponse updateProfile(UUID mentorId, UpdateMentorProfileRequest request) {
		requireMentor(mentorId);
		MentorProfile profile = mentorProfileRepository.findOneById(mentorId)
				.orElseThrow(() -> new ResourceNotFoundException("Mentor profile not found: " + mentorId));

		profile.setProfessionalTitle(request.professionalTitle());
		profile.setCompany(request.company());
		profile.setAvailabilityLabel(request.availabilityLabel());
		profile.setResponseTimeLabel(request.responseTimeLabel());
		profile.setMeetingMode(request.meetingMode());
		profile.setCommunicationPreference(request.communicationPreference());
		profile.setMentoringStyle(request.mentoringStyle());
		profile.setFitLabel(request.fitLabel());
		profile.setExperienceLevel(request.experienceLevel());
		profile.setSectorExperience(request.sectorExperience());
		profile.setStartupStages(request.startupStages());
		profile.getSpecialties().clear();
		profile.getSpecialties().addAll(request.specialties());
		// Both are optional so an older client that never sends them cannot wipe them.
		if (request.menteeCapacity() != null) {
			profile.setMenteeCapacity(request.menteeCapacity());
		}
		if (request.experiences() != null) {
			profile.getExperiences().clear();
			request.experiences().forEach(experience -> profile.getExperiences().add(experience.toEntity()));
		}
		if (request.about() != null) {
			profile.setAbout(request.about().trim());
		}
		if (request.meetingFrequency() != null) {
			profile.setMeetingFrequency(request.meetingFrequency());
		}
		if (request.prepFileRequirement() != null) {
			profile.setPrepFileRequirement(request.prepFileRequirement());
		}
		if (request.languages() != null) {
			profile.getLanguages().clear();
			request.languages().stream().distinct().forEach(profile.getLanguages()::add);
		}
		if (request.offerings() != null) {
			profile.getOfferings().clear();
			profile.getOfferings().addAll(TextUtils.normalizedList(request.offerings()));
		}
		if (request.programRoles() != null) {
			request.programRoles().forEach(programRole -> updateProgramRole(mentorId, programRole));
		}

		return withActivity(List.of(mentorProfileRepository.saveAndFlush(profile))).getFirst();
	}

	@Transactional
	public void deleteAvailabilitySlot(UUID mentorId, UUID slotId) {
		requireMentor(mentorId);
		MentorAvailabilitySlot slot = mentorAvailabilitySlotRepository.findById(slotId)
				.orElseThrow(() -> new ResourceNotFoundException("Availability slot not found: " + slotId));
		if (!slot.getMentor().getId().equals(mentorId)) {
			throw new InvalidRequestException("This slot does not belong to the given mentor");
		}
		if (meetingRepository.existsByMentorIdAndScheduledAtAndStatus(mentorId, slot.getStartsAt(), MeetingStatus.SCHEDULED)) {
			throw new InvalidRequestException("Cannot remove a slot that already has a scheduled meeting");
		}
		mentorAvailabilitySlotRepository.delete(slot);
	}

	private void updateProgramRole(UUID mentorId, MentorProgramRoleRequest programRole) {
		ProgramMentor membership = programMentorRepository
				.findByProgramIdAndMentorId(programRole.programId(), mentorId)
				.filter(row -> row.getStatus() == ProgramMentorStatus.ACCEPTED)
				.orElseThrow(() -> new InvalidRequestException("You are not a mentor in this program."));
		String role = TextUtils.trim(programRole.role());
		membership.setRole(role == null || role.isBlank() ? null : role);
		programMentorRepository.save(membership);
	}

	private AppUser requireMentor(UUID mentorId) {
		AppUser mentor = domainLookupService.requireUser(mentorId);
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("User is not a mentor: " + mentorId);
		}
		return mentor;
	}
}
