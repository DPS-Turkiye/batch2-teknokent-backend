package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.responses.MentorProfileResponse;
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
}
