package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.MentorProfileResponse;
import com.dps.teknokent.dto.responses.MentorEvidenceResponse;
import com.dps.teknokent.entity.MentorProfile;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.MentorProfileRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorProfileService {

	private final MentorProfileRepository mentorProfileRepository;

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAll() {
		return mentorProfileRepository.findAllByOrderBySortOrderAsc().stream()
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<MentorProfileResponse> findAllExcept(UUID excludedMentorId) {
		return mentorProfileRepository.findAllByOrderBySortOrderAsc().stream()
				.filter(profile -> !profile.getId().equals(excludedMentorId))
				.map(this::toResponse)
				.toList();
	}

	@Transactional(readOnly = true)
	public MentorProfileResponse findById(UUID id) {
		return mentorProfileRepository.findOneById(id)
				.map(this::toResponse)
				.orElseThrow(() -> new ResourceNotFoundException("Mentor not found: " + id));
	}

	private MentorProfileResponse toResponse(MentorProfile profile) {
		return new MentorProfileResponse(
				profile.getId(),
				profile.getUser().getFullName(),
				profile.getUser().getEmail(),
				profile.getInitials(),
				profile.getProfessionalTitle(),
				profile.getCompany(),
				profile.getAvailabilityLabel(),
				profile.getResponseTimeLabel(),
				profile.getMeetingMode(),
				profile.getCommunicationPreference(),
				profile.getMentoringStyle(),
				profile.getFitLabel(),
				profile.getExperienceLevel(),
				profile.getSectorExperience(),
				profile.getStartupStages(),
				List.copyOf(profile.getSpecialties()),
				List.copyOf(profile.getRecommendationReasons()),
				profile.getConfirmedEvidence().stream()
						.map(evidence -> new MentorEvidenceResponse(
								evidence.getCategory(), evidence.getClaim(), evidence.getQuote(),
								evidence.getSourcePage(), evidence.getProfileVersion()))
						.toList());
	}
}
