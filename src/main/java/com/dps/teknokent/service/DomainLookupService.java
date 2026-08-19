package com.dps.teknokent.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Meeting;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.AppUserRepository;
import com.dps.teknokent.repository.MeetingRepository;
import com.dps.teknokent.repository.OrganizationRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DomainLookupService {

	private final AppUserRepository appUserRepository;
	private final OrganizationRepository organizationRepository;
	private final MeetingRepository meetingRepository;

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
	public Meeting requireMeeting(UUID id) {
		return meetingRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Meeting not found: " + id));
	}
}
