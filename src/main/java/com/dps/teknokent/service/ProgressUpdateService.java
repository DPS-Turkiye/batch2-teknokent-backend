package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateProgressUpdateRequest;
import com.dps.teknokent.dto.responses.ProgressUpdateResponse;
import com.dps.teknokent.entity.AppUser;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.entity.ProgressUpdate;
import com.dps.teknokent.repository.ProgressUpdateRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProgressUpdateService {

	private final ProgressUpdateRepository progressUpdateRepository;
	private final DomainLookupService domainLookupService;
	private final StartupAccessService startupAccessService;

	@Transactional(readOnly = true)
	public List<ProgressUpdateResponse> findByOrganization(UUID organizationId, UUID actorId) {
		domainLookupService.requireOrganization(organizationId);
		startupAccessService.requireTeamOrStaff(actorId, organizationId);
		return progressUpdateRepository.findByOrganizationIdOrderByRecordedAtDesc(organizationId).stream()
				.map(ProgressUpdateResponse::fromEntity)
				.toList();
	}

	/** Written by the acting caller, who must be on the team or its staff; the body's authorId is not trusted. */
	@Transactional
	public ProgressUpdateResponse create(UUID organizationId, UUID actorId, CreateProgressUpdateRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		AppUser author = startupAccessService.requireTeamOrStaff(actorId, organizationId);
		ProgressUpdate update = ProgressUpdate.fromRequest(request, organization, author);
		return ProgressUpdateResponse.fromEntity(progressUpdateRepository.saveAndFlush(update));
	}
}
