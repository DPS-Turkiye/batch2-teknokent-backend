package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.domain.UserRole;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.MentorshipRequest;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.InvalidRequestException;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.MentorshipRequestRepository;
import com.dps.teknokent.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DomainLookupService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final MeetingRepository meetingRepository;
	private final MentorshipRequestRepository mentorshipRequestRepository;

	@Transactional(readOnly = true)
	public AppUser requireUser(UUID id) {
		return appUserRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
	}

	@Transactional(readOnly = true)
	public Organization requireOrganization(UUID id) {
		return organizationRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Organization not found: " + id));
	}

	@Transactional(readOnly = true)
	public AppUser requireOrganizationMentee(UUID organizationId) {
		return appUserRepository.findByOrganizationIdOrderByFullNameAsc(organizationId).stream()
				.filter(user -> user.getRole() == UserRole.MENTEE)
				.findFirst()
				.orElseThrow(() -> new InvalidRequestException(
						"No mentee found in the startup to attach this calendar meeting"));
	}

	@Transactional(readOnly = true)
	public Meeting requireMeeting(UUID id) {
		return meetingRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + id));
	}

	@Transactional(readOnly = true)
	public MentorshipRequest requireMentorshipRequest(UUID id) {
		return mentorshipRequestRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Mentorship request not found: " + id));
	}
}
