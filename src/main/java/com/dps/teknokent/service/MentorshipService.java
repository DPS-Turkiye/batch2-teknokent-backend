package com.dps.teknokent.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.dto.requests.CreateMentorshipRequest;
import com.dps.teknokent.dto.responses.MentorshipResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Mentorship;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.repository.MentorshipRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MentorshipService {

	private final MentorshipRepository mentorshipRepository;
	private final DomainLookupService domainLookupService;

	@Transactional(readOnly = true)
	public List<MentorshipResponse> findAll() {
		return mentorshipRepository.findAllByOrderByCreatedAtDesc().stream()
				.map(MentorshipResponse::fromEntity)
				.toList();
	}

	@Transactional
	public MentorshipResponse create(CreateMentorshipRequest request) {
		if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
			throw new InvalidRequestException("endDate must be on or after startDate");
		}

		AppUser mentor = domainLookupService.requireUser(request.mentorId());
		if (mentor.getRole() != UserRole.MENTOR) {
			throw new InvalidRequestException("Selected user does not have the MENTOR role");
		}

		Mentorship mentorship = Mentorship.fromRequest(request, mentor,
				domainLookupService.requireOrganization(request.organizationId()));
		return MentorshipResponse.fromEntity(mentorshipRepository.save(mentorship));
	}
}
