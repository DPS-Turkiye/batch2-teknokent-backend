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
				.map(this::toResponse)
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

		Mentorship mentorship = new Mentorship();
		mentorship.setMentor(mentor);
		mentorship.setOrganization(domainLookupService.requireOrganization(request.organizationId()));
		mentorship.setStatus(request.status());
		mentorship.setStartDate(request.startDate());
		mentorship.setEndDate(request.endDate());
		mentorship.setGoals(request.goals());
		return toResponse(mentorshipRepository.save(mentorship));
	}

	private MentorshipResponse toResponse(Mentorship mentorship) {
		return new MentorshipResponse(
				mentorship.getId(),
				mentorship.getMentor().getId(),
				mentorship.getMentor().getFullName(),
				mentorship.getOrganization().getId(),
				mentorship.getOrganization().getName(),
				mentorship.getStatus(),
				mentorship.getStartDate(),
				mentorship.getEndDate(),
				mentorship.getGoals());
	}
}
