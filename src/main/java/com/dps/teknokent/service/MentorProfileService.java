package com.dps.teknokent.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilityRuleRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilityRuleResponse;
import com.dps.teknokent.entity.MentorAvailabilityRule;
import com.dps.teknokent.repository.MentorAvailabilityRuleRepository;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserAccountStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.requests.UpdateMentorProfileRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
import com.dps.teknokent.entity.MentorProfile;
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
	private final MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	private final MeetingRepository meetingRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAll() {
		return approvedMentors().stream()
				.map(MentorProfileResponse::fromEntity)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAllExcept(UUID excludedMentorId) {
		return approvedMentors().stream()
				.filter(profile -> !profile.getId().equals(excludedMentorId))
				.map(MentorProfileResponse::fromEntity)
				.toList();
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

		List<MentorAvailabilitySlot> generated =
				mentorAvailabilitySlotRepository.findByRuleIdAndStartsAtAfter(ruleId, Instant.now());
		List<MentorAvailabilitySlot> removable = generated.stream()
				.filter(slot -> !isBooked(slot))
				.toList();
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

	private List<MentorProfile> approvedMentors() {
		return mentorProfileRepository.findAllByUserStatusOrderBySortOrderAsc(UserAccountStatus.APPROVED);
	}

	@Transactional(readOnly = true)
	public MentorProfileResponse findById(UUID id) {
		return mentorProfileRepository.findOneById(id)
				.map(MentorProfileResponse::fromEntity)
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

		return MentorProfileResponse.fromEntity(mentorProfileRepository.saveAndFlush(profile));
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

	private AppUser requireMentor(UUID mentorId) {
		AppUser mentor = domainLookupService.requireUser(mentorId);
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("User is not a mentor: " + mentorId);
		}
		return mentor;
	}
}
