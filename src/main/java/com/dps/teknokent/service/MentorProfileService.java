package com.dps.teknokent.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.MeetingStatus;
import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorAvailabilitySlotRequest;
import com.dps.teknokent.dto.responses.MentorAvailabilitySlotResponse;
import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.MentorAvailabilitySlot;
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
	private final MentorAvailabilitySlotRepository mentorAvailabilitySlotRepository;
	private final MeetingRepository meetingRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAll() {
		return mentorProfileRepository.findAllByOrderBySortOrderAsc().stream()
				.map(MentorProfileResponse::fromEntity)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAllExcept(UUID excludedMentorId) {
		return mentorProfileRepository.findAllByOrderBySortOrderAsc().stream()
				.filter(profile -> !profile.getId().equals(excludedMentorId))
				.map(MentorProfileResponse::fromEntity)
				.toList();
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
