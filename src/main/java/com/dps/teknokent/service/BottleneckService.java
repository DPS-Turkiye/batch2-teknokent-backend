package com.dps.teknokent.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dps.teknokent.dto.requests.CreateBottleneckRequest;
import com.dps.teknokent.dto.requests.UpdateBottleneckRequest;
import com.dps.teknokent.dto.responses.BottleneckResponse;
import com.dps.teknokent.entity.Bottleneck;
import com.dps.teknokent.entity.Organization;
import com.dps.teknokent.exception.ResourceNotFoundException;
import com.dps.teknokent.repository.BottleneckRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BottleneckService {

	private final BottleneckRepository bottleneckRepository;
	private final DomainLookupService domainLookupService;
	private final StartupAccessService startupAccessService;

	@Transactional(readOnly = true)
	public List<BottleneckResponse> findByOrganization(UUID organizationId, UUID actorId) {
		domainLookupService.requireOrganization(organizationId);
		startupAccessService.requireTeamOrStaff(actorId, organizationId);
		return bottleneckRepository.findByOrganizationIdOrderByCreatedAtDesc(organizationId).stream()
				.map(BottleneckResponse::fromEntity)
				.toList();
	}

	@Transactional
	public BottleneckResponse create(UUID organizationId, UUID actorId, CreateBottleneckRequest request) {
		Organization organization = domainLookupService.requireOrganization(organizationId);
		startupAccessService.requireTeamOrStaff(actorId, organizationId);
		Bottleneck bottleneck = Bottleneck.fromRequest(request, organization,
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		return BottleneckResponse.fromEntity(bottleneckRepository.saveAndFlush(bottleneck));
	}

	@Transactional
	public BottleneckResponse update(UUID id, UUID actorId, UpdateBottleneckRequest request) {
		Bottleneck bottleneck = bottleneckRepository.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Bottleneck not found: " + id));
		startupAccessService.requireTeamOrStaff(actorId, bottleneck.getOrganization().getId());

		bottleneck.apply(request,
				request.ownerId() == null ? null : domainLookupService.requireUser(request.ownerId()));
		return BottleneckResponse.fromEntity(bottleneckRepository.saveAndFlush(bottleneck));
	}
}
